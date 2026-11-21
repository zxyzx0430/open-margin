package com.bookkeeping.app.update

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import com.bookkeeping.app.i18n.AppLanguage
import java.io.File

/**
 * Invisible hand-off used by the "downloaded" notification: re-checks the
 * downloaded file, then opens the system installer through the same
 * FileProvider path as the in-app dialog. Android still asks the user to
 * confirm the install — there is no silent installation.
 */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Last-line package gate: a test build must never open the installer,
        // even if some stale notification or explicit intent reaches here.
        if (!SiteUpdatePolicy.canAutoDownload(packageName)) {
            finish()
            return
        }
        val fileName = intent.getStringExtra(EXTRA_APK_FILE_NAME)
        val safeName = fileName ?: run {
            finish()
            return
        }
        if (!UpdateUrlPolicy.isSafeApkName(safeName)) {
            finish()
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: run {
            toast("没有找到已下载的安装包，请重新下载")
            finish()
            return
        }
        val apk = File(dir, safeName)
        if (!apk.isFile) {
            toast("没有找到已下载的安装包，请重新下载")
            finish()
            return
        }
        if (!ApkSignatureVerifier.matchesInstalled(this, apk)) {
            runCatching { apk.delete() }
            toast("安装包校验失败，已删除")
            finish()
            return
        }
        when (UpdateInstallFlow.openInstaller(this, apk)) {
            UpdateInstallFlow.InstallOutcome.OPENED_INSTALLER -> Unit
            UpdateInstallFlow.InstallOutcome.NEEDS_PERMISSION -> {
                UpdateInstallFlow.openInstallPermissionSettings(this)
                toast("请允许「安装未知应用」，返回后点「安装」")
            }
            UpdateInstallFlow.InstallOutcome.NO_INSTALLER ->
                toast("没有找到系统安装程序，请用文件管理器打开下载的 APK")
        }
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, AppLanguage.tr(this, message), Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_APK_FILE_NAME = "apk_file_name"
    }
}
