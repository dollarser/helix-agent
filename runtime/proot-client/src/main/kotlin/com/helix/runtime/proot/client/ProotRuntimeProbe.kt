package com.helix.runtime.proot.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import java.security.MessageDigest

/**
 * Narrow seam over [PackageManager] for the companion's LOCAL (no-bind) state
 * (HXA-083; ADR-0007 section 6.7 step 1: "主 App 先检查 package/启用状态/签名").
 * Production uses [PackageManagerProotRuntimeProbe]; JVM tests inject a fake so
 * the full state matrix (installed/disabled/force-stopped/signature) is provable
 * off-device.
 *
 * Every answer is a fresh PackageManager query — the companion's state may have
 * changed (user force-stop, uninstall) between supervisor calls, and the
 * supervisor must never cache it.
 */
interface ProotRuntimeProbe {
    fun isInstalled(): Boolean

    /** True when the package is in the force-stopped state (only the user-gated repair entry recovers it). */
    fun isStopped(): Boolean

    fun isEnabled(): Boolean

    /** The companion's current signing-certificate SHA-256 digests (empty when unknown). */
    fun signingCertificateSha256s(): List<String>

    /** True when the companion's certificates are signed by this app's own set key. */
    fun isSameSigningSet(): Boolean
}

/**
 * The production probe: one [PackageManager] query per call, fail-closed on error.
 * [packageName] defaults to the companion; the supervisor also constructs one for
 * its OWN package to derive the signed-set's own certificate digests.
 */
class PackageManagerProotRuntimeProbe(
    private val context: Context,
    private val packageName: String = ProotRuntimeProtocol.RUNTIME_PACKAGE,
) : ProotRuntimeProbe {
    override fun isInstalled(): Boolean = packageInfo() != null

    override fun isStopped(): Boolean = stoppedFlag() ?: false

    /** The FLAG_STOPPED bit of the probed package; null when it is not installed. */
    private fun stoppedFlag(): Boolean? {
        val appInfo = packageInfo()?.applicationInfo ?: return null
        return appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
    }

    override fun isEnabled(): Boolean {
        val info = packageInfo() ?: return false
        return info.applicationInfo?.enabled ?: false
    }

    override fun signingCertificateSha256s(): List<String> {
        val info = packageInfo() ?: return emptyList()
        val certs: Array<Signature>? = info.signingInfo?.apkContentsSigners
        return certs.orEmpty().map { cert ->
            MessageDigest
                .getInstance("SHA-256")
                .digest(cert.toByteArray())
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }

    override fun isSameSigningSet(): Boolean {
        val own = PackageManagerProotRuntimeProbe(context, context.packageName).signingCertificateSha256s()
        val peer = signingCertificateSha256s()
        return own.isNotEmpty() && peer.any { it in own }
    }

    // @Suppress("SwallowedException") — a NameNotFound IS the answer: "not
    // installed" is a stable probe result, not an error condition.
    @Suppress("SwallowedException")
    private fun packageInfo(): android.content.pm.PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
}
