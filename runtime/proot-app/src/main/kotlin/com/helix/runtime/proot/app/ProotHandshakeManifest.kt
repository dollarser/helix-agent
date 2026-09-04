package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.requireBaseline
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.RuntimeTargetDescriptor
import com.helix.runtime.proot.ipc.RuntimeTargetDescriptorCodec

/**
 * Builds the companion's handshake manifest (HXA-083; ADR-0007 decision 2).
 *
 * The descriptor is derived FRESH from the embedded lock on every call: the
 * canonical lock fingerprint, the baseline ABI, the companion's versionName, the
 * protocol version and the closed capability set. Nothing is cached in-process —
 * a rebinding process must not inherit authority from a previous incarnation.
 *
 * This object is total: it either returns valid manifest bytes or throws, and the
 * service maps any throw to REPLY_MANIFEST_FAILED. The device test reuses the
 * same builder to prove the manifest matches the embedded lock byte-for-byte.
 */
object ProotHandshakeManifest {
    fun build(context: Context): ByteArray {
        val lockText =
            context.assets
                .open("runtime/runtime-lock.json")
                .bufferedReader()
                .use { it.readText() }
        val lock = RuntimeLockCodec.parse(lockText)
        lock.requireBaseline()
        val descriptor =
            RuntimeTargetDescriptor(
                protocolVersion = ProotRuntimeProtocol.PROTOCOL_VERSION,
                runtimeVersion =
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        ?: error("companion versionName missing"),
                abi = lock.abi.wire,
                lockSha256 = RuntimeLockCodec.sha256Hex(lock),
                capabilities = listOf("handshake", "jobs"),
            )
        return RuntimeTargetDescriptorCodec.encode(descriptor).encodeToByteArray()
    }
}
