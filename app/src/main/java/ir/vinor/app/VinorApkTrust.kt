package ir.vinor.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/**
 * Verify a downloaded update APK matches this app's package name and signing certs.
 *
 * Note: [PackageManager.getPackageArchiveInfo] often returns null [SigningInfo] on
 * some API levels — we fall back to classic signatures and JAR META-INF certs.
 */
object VinorApkTrust {

    private const val TAG = "VinorApkTrust"

    fun isTrustedUpdateApk(context: Context, file: File?): Boolean {
        if (file == null || !file.isFile) return false
        if (!VinorApkUpdateInstaller.isValidApkFile(file)) return false
        return try {
            val pm = context.packageManager
            val expectedPkg = context.packageName
            val archive = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
            archive.applicationInfo?.apply {
                sourceDir = file.absolutePath
                publicSourceDir = file.absolutePath
            }
            val archivePkg = archive.packageName?.trim().orEmpty()
            if (archivePkg != expectedPkg) {
                Log.w(TAG, "package mismatch: archive=$archivePkg expected=$expectedPkg")
                return false
            }
            val installed = installedSigningDigests(pm, expectedPkg)
            val incoming = archiveSigningDigests(pm, file)
            if (installed.isEmpty() || incoming.isEmpty()) {
                Log.w(
                    TAG,
                    "missing signing certs installed=${installed.size} incoming=${incoming.size}",
                )
                return false
            }
            val match = incoming.any { dig -> installed.contains(dig) }
            if (!match) {
                Log.w(TAG, "signing cert mismatch for ${file.name}")
            }
            match
        } catch (e: Exception) {
            Log.w(TAG, "isTrustedUpdateApk failed", e)
            false
        }
    }

    /**
     * اگر versionCode فایل ≤ نسخهٔ نصب‌شده باشد، پیام فارسی برمی‌گرداند؛ وگرنه null.
     */
    fun downgradeOrConflictMessage(context: Context, file: File?): String? {
        if (file == null || !file.isFile) return "فایل نصب آماده نیست"
        return try {
            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            val incomingCode = packageVersionCode(archive)
            val installed = pm.getPackageInfo(context.packageName, 0)
            val installedCode = packageVersionCode(installed)
            if (incomingCode > 0L && installedCode > 0L && incomingCode <= installedCode) {
                "نسخهٔ این فایل از نسخهٔ نصب‌شده قدیمی‌تر یا یکسان است؛ نصب ممکن نیست"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "downgradeOrConflictMessage failed", e)
            null
        }
    }

    private fun packageVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun installedSigningDigests(pm: PackageManager, packageName: String): Set<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val fromSi = digestsFromSigningInfo(info.signingInfo)
                if (fromSi.isNotEmpty()) return fromSi
            }
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            digestsFromSignatures(info.signatures)
        } catch (e: Exception) {
            Log.w(TAG, "installedSigningDigests failed", e)
            emptySet()
        }
    }

    private fun archiveSigningDigests(pm: PackageManager, file: File): Set<String> {
        // 1) Modern signing info from archive (may be empty on many devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val info = pm.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                val fromSi = digestsFromSigningInfo(info?.signingInfo)
                if (fromSi.isNotEmpty()) return fromSi
            } catch (e: Exception) {
                Log.w(TAG, "archive GET_SIGNING_CERTIFICATES failed", e)
            }
        }
        // 2) Classic Signature[] — usually works for APK archives
        try {
            @Suppress("DEPRECATION")
            val info: PackageInfo? = pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNATURES,
            )
            @Suppress("DEPRECATION")
            val fromSigs = digestsFromSignatures(info?.signatures)
            if (fromSigs.isNotEmpty()) return fromSigs
        } catch (e: Exception) {
            Log.w(TAG, "archive GET_SIGNATURES failed", e)
        }
        // 3) JAR v1 signer blocks in META-INF
        val fromJar = jarSignerDigests(file)
        if (fromJar.isNotEmpty()) return fromJar
        return emptySet()
    }

    private fun digestsFromSigningInfo(si: SigningInfo?): Set<String> {
        if (si == null) return emptySet()
        val signers = if (si.hasMultipleSigners()) {
            si.apkContentsSigners
        } else {
            // Include current + history so key-rotation updates still match.
            val history = si.signingCertificateHistory
            if (history != null && history.isNotEmpty()) history else si.apkContentsSigners
        }
        return digestsFromSignatures(signers)
    }

    private fun digestsFromSignatures(signatures: Array<Signature>?): Set<String> {
        if (signatures.isNullOrEmpty()) return emptySet()
        return signatures.mapNotNull { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun jarSignerDigests(file: File): Set<String> {
        return try {
            val out = linkedSetOf<String>()
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.uppercase()
                    if (!name.startsWith("META-INF/")) continue
                    if (
                        !name.endsWith(".RSA") &&
                        !name.endsWith(".DSA") &&
                        !name.endsWith(".EC")
                    ) {
                        continue
                    }
                    zip.getInputStream(entry).use { ins ->
                        val certs = CertificateFactory.getInstance("X.509")
                            .generateCertificates(ins)
                        for (cert in certs) {
                            sha256Hex(cert.encoded)?.let { out.add(it) }
                        }
                    }
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "jarSignerDigests failed", e)
            emptySet()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String? {
        return try {
            val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
            buildString(dig.size * 2) {
                for (b in dig) {
                    append("%02x".format(b.toInt() and 0xff))
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
