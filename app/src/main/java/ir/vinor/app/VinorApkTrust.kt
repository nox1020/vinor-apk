package ir.vinor.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Verify a downloaded update APK matches this app's package name and signing certs.
 */
object VinorApkTrust {

    private const val TAG = "VinorApkTrust"

    fun isTrustedUpdateApk(context: Context, file: File?): Boolean {
        if (file == null || !file.isFile) return false
        if (!VinorApkUpdateInstaller.isValidApkFile(file)) return false
        return try {
            val pm = context.packageManager
            val expectedPkg = context.packageName
            val archive = pm.getPackageArchiveInfo(file.absolutePath, archiveFlags())
                ?: return false
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
                Log.w(TAG, "missing signing certs installed=${installed.size} incoming=${incoming.size}")
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

    private fun archiveFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun installedSigningDigests(pm: PackageManager, packageName: String): Set<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return emptySet()
                val signers = if (si.hasMultipleSigners()) {
                    si.apkContentsSigners
                } else {
                    si.signingCertificateHistory
                }
                signers?.mapNotNull { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.mapNotNull { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "installedSigningDigests failed", e)
            emptySet()
        }
    }

    private fun archiveSigningDigests(pm: PackageManager, file: File): Set<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ) ?: return emptySet()
                val si = info.signingInfo ?: return emptySet()
                val signers = if (si.hasMultipleSigners()) {
                    si.apkContentsSigners
                } else {
                    si.signingCertificateHistory
                }
                signers?.mapNotNull { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.GET_SIGNATURES,
                ) ?: return emptySet()
                @Suppress("DEPRECATION")
                info.signatures?.mapNotNull { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "archiveSigningDigests failed", e)
            emptySet()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String? {
        return try {
            val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
            dig.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
