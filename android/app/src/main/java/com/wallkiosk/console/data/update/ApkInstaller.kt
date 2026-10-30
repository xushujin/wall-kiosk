package com.wallkiosk.console.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

data class ApkInspection(val ok: Boolean, val reason: String?)

/**
 * 下载完的包在装之前先验一遍。
 *
 * 验两件事：包名是不是本应用、签名和已安装版本是否一致。
 * 第二条是重点：HTTPS 能防路上被换，但防不了源被换（比如仓库被投毒、镜像站返回了别的包）。
 * 签名对不上，系统本来也会拒装，但那时候只会弹一句"应用未安装"，看不出原因。
 *
 * API 28 以下拿不到 SigningInfo，只验包名——这部分在文档里标了未覆盖。
 */
object ApkInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val HEX = "0123456789abcdef"

    /**
     * getPackageInfo / getPackageArchiveInfo 的 (String, Int) 重载在 API 33 被标记废弃，
     * 新的 PackageInfoFlags 重载要 API 33 才有。minSdk 是 26，只能继续用旧重载，
     * 所以这里显式压掉废弃警告——不是忽略警告，是确实没有别的选择。
     */
    @Suppress("DEPRECATION")
    fun inspect(context: Context, apk: File): ApkInspection {
        if (!apk.exists() || apk.length() == 0L) return ApkInspection(false, "安装包是空的")

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }

        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return ApkInspection(false, "这个文件不是有效的 APK")

        if (archive.packageName != context.packageName) {
            return ApkInspection(false, "包名对不上：${archive.packageName}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installed = runCatching {
                pm.getPackageInfo(context.packageName, flags).signingInfo
            }.getOrNull() ?: return ApkInspection(false, "读不到已安装版本的签名")

            val downloaded = archive.signingInfo
                ?: return ApkInspection(false, "读不到下载包的签名")

            val mine = fingerprints(installed)
            val theirs = fingerprints(downloaded)
            if (mine.isEmpty() || mine != theirs) {
                return ApkInspection(false, "签名和已安装版本不一致，这个包装不上去")
            }
        }

        return ApkInspection(true, null)
    }

    /** 系统设置里有没有允许本应用安装未知来源的包 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳去系统设置开"安装未知应用"，返回是否成功拉起 */
    fun openInstallSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 拉起系统安装器。必须走 content:// —— targetSdk 24 以后 file:// 会直接抛异常 */
    fun install(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun fingerprints(info: SigningInfo): Set<String> {
        val signers = runCatching { info.apkContentsSigners }.getOrNull() ?: return emptySet()
        return signers.mapNotNull { signature ->
            runCatching { sha256(signature.toByteArray()) }.getOrNull()
        }.toSet()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
