package com.bookkeeping.app.backup.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class WebDavPreset(
    val label: String,
    val defaultUrl: String,
    val passwordHint: String,
) {
    JIANGUOYUN(
        label = "坚果云",
        defaultUrl = "https://dav.jianguoyun.com/dav/清衡备份/",
        passwordHint = "请填写坚果云生成的第三方应用密码，不要填登录密码",
    ),
    SYNOLOGY(
        label = "群晖 NAS",
        defaultUrl = "https://your-nas:5006/清衡备份/",
        passwordHint = "请填写群晖账号密码或专用 WebDAV 账号密码",
    ),
    ALIST(
        label = "Alist",
        defaultUrl = "https://your-alist/dav/清衡备份/",
        passwordHint = "请填写 Alist 后台配置的 WebDAV 密码",
    ),
    CUSTOM(
        label = "自定义",
        defaultUrl = "https://",
        passwordHint = "请填写 WebDAV 授权密码",
    ),
}

data class WebDavSettingsSnapshot(
    val preset: WebDavPreset = WebDavPreset.JIANGUOYUN,
    val serverUrl: String = WebDavPreset.JIANGUOYUN.defaultUrl,
    val username: String = "",
    val password: String = "",
    val autoEnabled: Boolean = false,
    val wifiOnly: Boolean = false,
    val lastBackupMs: Long = 0L,
    val lastBackupStatus: String = "",
    val lastBackupWatermark: String = "",
) {
    val isConfigured: Boolean
        get() = serverUrl.trim().startsWith("http") && username.isNotBlank() && password.isNotBlank()
}

class WebDavPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): WebDavSettingsSnapshot {
        val presetName = prefs.getString(KEY_PRESET, WebDavPreset.JIANGUOYUN.name)
        val preset = WebDavPreset.entries.firstOrNull { it.name == presetName } ?: WebDavPreset.JIANGUOYUN
        val encryptedPwd = prefs.getString(KEY_PASSWORD_ENC, "").orEmpty()
        return WebDavSettingsSnapshot(
            preset = preset,
            serverUrl = prefs.getString(KEY_URL, preset.defaultUrl).orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = if (encryptedPwd.isNotBlank()) decrypt(encryptedPwd) else "",
            autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            lastBackupMs = prefs.getLong(KEY_LAST_BACKUP_MS, 0L),
            lastBackupStatus = prefs.getString(KEY_LAST_STATUS, "").orEmpty(),
            lastBackupWatermark = prefs.getString(KEY_LAST_WATERMARK, "").orEmpty(),
        )
    }

    fun saveConfig(
        preset: WebDavPreset,
        serverUrl: String,
        username: String,
        password: String,
        autoEnabled: Boolean,
        wifiOnly: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_PRESET, preset.name)
            .putString(KEY_URL, serverUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD_ENC, if (password.isNotEmpty()) encrypt(password) else "")
            .putBoolean(KEY_AUTO_ENABLED, autoEnabled)
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .apply()
    }

    fun recordBackupOutcome(timeMs: Long, status: String, watermark: String? = null) {
        prefs.edit().apply {
            if (timeMs > 0L) putLong(KEY_LAST_BACKUP_MS, timeMs)
            putString(KEY_LAST_STATUS, status)
            if (watermark != null) putString(KEY_LAST_WATERMARK, watermark)
        }.apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGen.generateKey()
    }

    private fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val buf = ByteBuffer.allocate(4 + iv.size + ct.size)
            .putInt(iv.size)
            .put(iv)
            .put(ct)
            .array()
        Base64.encodeToString(buf, Base64.NO_WRAP)
    }.getOrDefault("")

    private fun decrypt(encoded: String): String = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(raw)
        val ivLen = buf.getInt()
        if (ivLen !in 12..32) return ""
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val ct = ByteArray(buf.remaining())
        buf.get(ct)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }.getOrDefault("")

    companion object {
        private const val PREFS_NAME = "webdav_backup_prefs"
        private const val KEY_PRESET = "preset"
        private const val KEY_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD_ENC = "password_enc"
        private const val KEY_AUTO_ENABLED = "auto_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_LAST_BACKUP_MS = "last_backup_ms"
        private const val KEY_LAST_STATUS = "last_backup_status"
        private const val KEY_LAST_WATERMARK = "last_backup_watermark"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "open_margin_webdav_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
