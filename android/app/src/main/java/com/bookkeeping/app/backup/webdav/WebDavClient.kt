package com.bookkeeping.app.backup.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    fun normalizedBaseUrl(): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun isValid(): Boolean =
        normalizedBaseUrl().startsWith("http://") || normalizedBaseUrl().startsWith("https://")
}

data class WebDavBackupFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

class WebDavException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class WebDavClient(
    private val sleepFn: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) {
    private val verifiedCollections = ConcurrentHashMap.newKeySet<String>()

    fun testConnection(config: WebDavConfig): Int {
        ensureCollection(config)
        return listBackups(config).size
    }

    fun ensureCollection(config: WebDavConfig) {
        val base = config.normalizedBaseUrl()
        if (!config.isValid()) {
            throw WebDavException(null, "网盘地址格式有误，请以 https:// 开头")
        }
        if (base in verifiedCollections) return
        val code = executeWithRetry {
            open(base, "MKCOL", config).run {
                connectTimeout = 10_000
                readTimeout = 15_000
                responseCode
            }
        }
        // 201 Created, 405 Method Not Allowed (already exists), 301/302/200/204 -> collection exists
        if (code in listOf(200, 201, 204, 301, 302, 405)) {
            verifiedCollections.add(base)
            return
        }
        throw mapHttpError(code, "无法创建或访问备份目录")
    }

    fun uploadZipAtomic(
        config: WebDavConfig,
        fileName: String,
        zipBytes: ByteArray,
    ) {
        ensureCollection(config)
        val base = config.normalizedBaseUrl()
        val finalUrl = "$base$fileName"
        val tmpUrl = "$base$fileName.tmp"

        val putTmpCode = executeWithRetry {
            val conn = open(tmpUrl, "PUT", config).apply {
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/zip")
                setFixedLengthStreamingMode(zipBytes.size)
            }
            conn.outputStream.use { it.write(zipBytes) }
            conn.responseCode
        }
        if (putTmpCode !in 200..299) {
            throw mapHttpError(putTmpCode, "上传临时备份文件失败")
        }

        val moveCode = executeWithRetry {
            val conn = open(tmpUrl, "MOVE", config).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Destination", finalUrl)
                setRequestProperty("Overwrite", "T")
            }
            conn.responseCode
        }
        if (moveCode in 200..299) return

        // Fallback if server doesn't support MOVE (e.g., some lightweight WebDAV proxies)
        val directPutCode = executeWithRetry {
            val conn = open(finalUrl, "PUT", config).apply {
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/zip")
                setFixedLengthStreamingMode(zipBytes.size)
            }
            conn.outputStream.use { it.write(zipBytes) }
            conn.responseCode
        }
        runCatching { deleteByUrl(config, tmpUrl) }
        if (directPutCode !in 200..299) {
            throw mapHttpError(directPutCode, "写入网盘备份文件失败")
        }
    }

    fun listBackups(config: WebDavConfig): List<WebDavBackupFile> {
        val base = config.normalizedBaseUrl()
        if (!config.isValid()) throw WebDavException(null, "网盘地址格式有误")
        val bodyBytes = PROPFIND_BODY.toByteArray(StandardCharsets.UTF_8)
        val result = executeWithRetryWithBody {
            val conn = open(base, "PROPFIND", config).apply {
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Depth", "1")
                setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                setFixedLengthStreamingMode(bodyBytes.size)
            }
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to text
        }
        if (result.first == 404) return emptyList()
        if (result.first !in 200..299) {
            throw mapHttpError(result.first, "读取网盘备份列表失败")
        }
        return parsePropfindXml(base, result.second)
            .filter { it.name.endsWith(".zip", ignoreCase = true) && !it.name.endsWith(".tmp", ignoreCase = true) }
            .sortedByDescending { it.modifiedMs }
    }

    fun downloadBackup(config: WebDavConfig, remoteUrl: String): ByteArray {
        val result = executeWithRetryBytes {
            val conn = open(remoteUrl, "GET", config).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            val bytes = if (code in 200..299) {
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    input.copyTo(out)
                    out.toByteArray()
                }
            } else {
                ByteArray(0)
            }
            code to bytes
        }
        if (result.first !in 200..299) {
            throw mapHttpError(result.first, "下载网盘备份失败")
        }
        return result.second
    }

    fun deleteByUrl(config: WebDavConfig, remoteUrl: String) {
        val code = executeWithRetry {
            open(remoteUrl, "DELETE", config).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }.responseCode
        }
        if (code !in 200..299 && code != 404) {
            throw mapHttpError(code, "清理旧备份失败")
        }
    }

    fun pruneOldBackups(config: WebDavConfig, keepCount: Int = 10): Int {
        val files = listBackups(config)
        if (files.size <= keepCount) return 0
        var deleted = 0
        files.drop(keepCount).forEach { old ->
            runCatching {
                deleteByUrl(config, old.url)
                deleted++
            }
        }
        return deleted
    }

    private fun open(urlString: String, method: String, config: WebDavConfig): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = method
        val token = Base64.getEncoder().encodeToString(
            "${config.username.trim()}:${config.password}".toByteArray(StandardCharsets.UTF_8),
        )
        conn.setRequestProperty("Authorization", "Basic $token")
        return conn
    }

    private fun executeWithRetry(call: () -> Int): Int {
        var attempt = 0
        var delayMs = 1_500L
        while (true) {
            val code = call()
            if ((code == 429 || code == 503) && attempt < 2) {
                attempt++
                sleepFn(delayMs)
                delayMs *= 2
                continue
            }
            return code
        }
    }

    private fun executeWithRetryWithBody(call: () -> Pair<Int, String>): Pair<Int, String> {
        var attempt = 0
        var delayMs = 1_500L
        while (true) {
            val res = call()
            if ((res.first == 429 || res.first == 503) && attempt < 2) {
                attempt++
                sleepFn(delayMs)
                delayMs *= 2
                continue
            }
            return res
        }
    }

    private fun executeWithRetryBytes(call: () -> Pair<Int, ByteArray>): Pair<Int, ByteArray> {
        var attempt = 0
        var delayMs = 1_500L
        while (true) {
            val res = call()
            if ((res.first == 429 || res.first == 503) && attempt < 2) {
                attempt++
                sleepFn(delayMs)
                delayMs *= 2
                continue
            }
            return res
        }
    }

    private fun mapHttpError(code: Int, fallback: String): WebDavException = when (code) {
        401 -> WebDavException(code, "账号或应用密码错误（401），请确认填写的是第三方应用密码")
        403 -> WebDavException(code, "网盘拒绝访问（403），可能触发了权限或频率限制")
        404 -> WebDavException(code, "网盘目录不存在（404），请检查地址路径")
        429, 503 -> WebDavException(code, "网盘请求过于频繁（$code），请稍后再试")
        507 -> WebDavException(code, "网盘剩余空间不足（507）")
        else -> WebDavException(code, "$fallback（HTTP $code）")
    }

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:getcontentlength/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>"""

        internal fun parsePropfindXml(baseUrl: String, xml: String): List<WebDavBackupFile> {
            if (xml.isBlank()) return emptyList()
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val baseUri = URI(baseUrl)
            val results = mutableListOf<WebDavBackupFile>()
            var currentTag = ""
            var href: String? = null
            var contentLength = 0L
            var lastModifiedMs = 0L
            var inResponse = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val local = parser.name?.lowercase(Locale.ROOT).orEmpty()
                        if (local == "response") {
                            inResponse = true
                            href = null
                            contentLength = 0L
                            lastModifiedMs = 0L
                        } else if (inResponse) {
                            currentTag = local
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inResponse) {
                            val text = parser.text?.trim().orEmpty()
                            when (currentTag) {
                                "href" -> if (text.isNotEmpty()) href = text
                                "getcontentlength" -> contentLength = text.toLongOrNull() ?: contentLength
                                "getlastmodified" -> parseHttpDate(text)?.let { lastModifiedMs = it }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val local = parser.name?.lowercase(Locale.ROOT).orEmpty()
                        if (local == "response" && inResponse) {
                            val rawHref = href
                            if (!rawHref.isNullOrBlank() && !rawHref.endsWith("/")) {
                                val resolved = runCatching { baseUri.resolve(rawHref).toString() }.getOrDefault(rawHref)
                                val name = rawHref.trimEnd('/').substringAfterLast('/')
                                if (name.isNotBlank()) {
                                    results += WebDavBackupFile(
                                        name = name,
                                        url = resolved,
                                        sizeBytes = contentLength,
                                        modifiedMs = lastModifiedMs,
                                    )
                                }
                            }
                            inResponse = false
                        }
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
            return results
        }

        private fun parseHttpDate(raw: String): Long? {
            if (raw.isBlank()) return null
            val patterns = listOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
            )
            for (pattern in patterns) {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val parsed = runCatching { fmt.parse(raw)?.time }.getOrNull()
                if (parsed != null) return parsed
            }
            return null
        }
    }
}
