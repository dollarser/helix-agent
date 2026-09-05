package com.helix.runtime.cli.app

import android.content.Context
import com.helix.runtime.cli.client.CliRuntimeProtocol
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

object CliCallerVerifier {
    @Suppress("ReturnCount") // Each identity/certificate rejection remains explicit and fail-closed.
    fun verify(
        context: Context,
        callingUid: Int,
    ): Boolean {
        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return false
        if (packages.size != 1 || packages.single() !in CliRuntimeProtocol.MAIN_APP_PACKAGES) return false
        val caller = certificates(context.packageManager, packages.single()) ?: return false
        val own = certificates(context.packageManager, context.packageName) ?: return false
        val expected = own.map(::digest).toSet()
        return expected.isNotEmpty() && caller.any { digest(it) in expected }
    }

    @Suppress("SwallowedException")
    private fun certificates(
        pm: PackageManager,
        packageName: String,
    ): Array<Signature>? {
        val info =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
        return info.signingInfo?.apkContentsSigners
    }

    private fun digest(signature: Signature): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
