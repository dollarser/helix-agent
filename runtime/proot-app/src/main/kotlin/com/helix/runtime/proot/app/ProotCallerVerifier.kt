package com.helix.runtime.proot.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import java.security.MessageDigest

/**
 * Service-side caller re-verification (architecture doc section 6.6 "Service 再校验
 * 调用 UID 对应包签名"). The platform signature permission already guarantees that
 * only an APK of the same signed set can attempt the bind; this check narrows
 * further: the caller UID must map to exactly ONE package of the main-app set, and
 * that package's signing certificate must match the set's own certificate.
 *
 * [verify] is pure logic over [PackageManager] so the device test can prove the
 * semantics (allowed-package match, certificate match/mismatch) with controlled
 * expectations. Any uncertainty (multi-package UID, missing cert info, no expected
 * digest) fails closed.
 */
object ProotCallerVerifier {
    @Suppress("ReturnCount") // one return per distinct rejection outcome

    fun verify(
        context: Context,
        callingUid: Int,
        allowedPackages: Set<String> = ProotRuntimeProtocol.MAIN_APP_PACKAGES,
        expectedCertSha256s: List<String> = selfCertSha256s(context),
    ): Boolean {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(callingUid)
        if (packages == null || packages.size != 1) return false
        val pkg = packages[0]
        if (pkg !in allowedPackages) return false
        val certs = signingCertificates(pm, pkg) ?: return false
        if (certs.isEmpty() || expectedCertSha256s.isEmpty()) return false
        return certs.any { cert -> sha256Hex(cert.toByteArray()) in expectedCertSha256s }
    }

    /** The signing-certificate digests of this own APK (the set's key). */
    fun selfCertSha256s(context: Context): List<String> {
        val certs = signingCertificates(context.packageManager, context.packageName) ?: return emptyList()
        return certs.map { sha256Hex(it.toByteArray()) }
    }

    /** The UID this process runs as (the companion's own UID in production). */
    fun ownUid(): Int = Process.myUid()

    // @Suppress("SwallowedException") — a NameNotFound during the caller check is
    // "no certificate", which the caller treats as REFUSE (fail-closed).
    @Suppress("SwallowedException")
    private fun signingCertificates(
        pm: PackageManager,
        pkg: String,
    ): Array<Signature>? {
        val info =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
        // apkContentsSigners = the CURRENT signing certificate(s) of the APK.
        return info.signingInfo?.apkContentsSigners
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
