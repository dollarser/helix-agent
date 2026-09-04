package com.helix.runtime.proot.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PermissionInfo
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.requireBaseline
import com.helix.runtime.proot.ipc.ProotHandshakeClient
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.ProotRuntimeServiceBinder
import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-083 device acceptance, companion side: the manifest contracts of the signed
 * set (signature permission, exported service/activity, NO launcher), the caller
 * verifier's accept/reject semantics against the REAL PackageManager, and the
 * hand-rolled handshake protocol exercised IN-PROCESS (local binder, no bind) so
 * the wire shape is proven on the Android runtime without a second APK.
 *
 * The cross-APK cold-bind E2E (real bind from com.helix.agent, process death,
 * force-stop recovery, startup-never-binds) lives in the :app developer flavor's
 * ProotRuntimeBindingE2eDeviceTest — it requires BOTH APKs of the signed set.
 */
@RunWith(AndroidJUnit4::class)
class ProotRuntimeBindingDeviceTest {
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val assets get() = targetContext.assets

    private fun readText(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    @Test
    fun theManifestDeclaresTheSignaturePermissionAndExportsServiceAndActivity() {
        val pm = targetContext.packageManager
        val permission = pm.getPermissionInfo(ProotRuntimeProtocol.PERMISSION_BIND, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permission.protectionLevel)

        val service =
            pm.getServiceInfo(
                ComponentName(ProotRuntimeProtocol.RUNTIME_PACKAGE, ProotRuntimeProtocol.SERVICE_CLASS),
                0,
            )
        assertTrue("service must be exported for cross-APK binds", service.exported)
        assertEquals(ProotRuntimeProtocol.PERMISSION_BIND, service.permission)

        val activity =
            pm.getActivityInfo(
                ComponentName(ProotRuntimeProtocol.RUNTIME_PACKAGE, ProotRuntimeProtocol.REPAIR_ACTIVITY_CLASS),
                0,
            )
        assertTrue("repair activity must be exported for the explicit user-gated entry", activity.exported)
        assertEquals(ProotRuntimeProtocol.PERMISSION_BIND, activity.permission)

        val launchers =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(ProotRuntimeProtocol.RUNTIME_PACKAGE),
                0,
            )
        assertTrue("the companion has NO launcher activity (ADR-0007)", launchers.isEmpty())
    }

    @Test
    fun theCallerVerifierAcceptsOnlyTheSignedMainAppSet() {
        val ownUid = Process.myUid()
        val ownPackage = targetContext.packageName
        val ownDigests = ProotCallerVerifier.selfCertSha256s(targetContext)
        assertTrue("own certificate digest must be readable", ownDigests.isNotEmpty())

        // Positive: a uid that maps to exactly one allowed package whose certificate
        // matches the set's key. The companion's own uid+package stands in for the
        // main app (same uid semantics, same signing key).
        assertTrue(ProotCallerVerifier.verify(targetContext, ownUid, setOf(ownPackage), ownDigests))

        // Negative: the uid maps to a package outside the allowed set.
        assertFalse(
            ProotCallerVerifier.verify(
                targetContext,
                ownUid,
                setOf("com.helix.agent"),
                ownDigests,
            ),
        )

        // Negative: package allowed, but the certificate is not the set's key.
        assertFalse(ProotCallerVerifier.verify(targetContext, ownUid, setOf(ownPackage), listOf("0".repeat(64))))

        // Negative: a uid that maps to a package the verifier cannot resolve.
        assertFalse(ProotCallerVerifier.verify(targetContext, -1, setOf("com.helix.agent"), ownDigests))
    }

    @Test
    fun theInProcessHandshakeServesTheLockConsistentDescriptor() {
        val binder =
            ProotRuntimeServiceBinder(
                manifestProvider = { ProotHandshakeManifest.build(targetContext) },
                callerVerifier = { true },
            )
        val outcome = ProotHandshakeClient.handshake(binder)
        val descriptor = (outcome as ProotHandshakeClient.Outcome.Descriptor).descriptor

        val lockText = readText("runtime/runtime-lock.json")
        val lock = RuntimeLockCodec.parse(lockText)
        lock.requireBaseline()
        assertEquals(RuntimeLockCodec.sha256Hex(lock), descriptor.lockSha256)
        assertEquals("arm64-v8a", descriptor.abi)
        assertEquals(ProotRuntimeProtocol.PROTOCOL_VERSION, descriptor.protocolVersion)
        assertEquals(listOf("handshake", "jobs"), descriptor.capabilities)
        val versionName = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName
        assertEquals(versionName, descriptor.runtimeVersion)
    }

    @Test
    fun aFailingManifestProviderYieldsManifestFailedNotACrash() {
        val binder =
            ProotRuntimeServiceBinder(
                manifestProvider = {
                    throw IllegalStateException("boom")
                },
                callerVerifier = { true },
            )
        val outcome = ProotHandshakeClient.handshake(binder)
        assertEquals(UnavailableCause.HANDSHAKE_FAILED, (outcome as ProotHandshakeClient.Outcome.Failed).cause)
    }

    @Test
    fun anOverCapManifestYieldsManifestFailedNotAPartialRead() {
        val binder =
            ProotRuntimeServiceBinder(
                manifestProvider = { ByteArray((ProotRuntimeProtocol.MAX_MANIFEST_BYTES + 1).toInt()) },
                callerVerifier = { true },
            )
        val outcome = ProotHandshakeClient.handshake(binder)
        assertEquals(UnavailableCause.HANDSHAKE_FAILED, (outcome as ProotHandshakeClient.Outcome.Failed).cause)
    }

    @Test
    fun theServerRejectsAnUnknownClientProtocolVersion() {
        val binder =
            ProotRuntimeServiceBinder(
                manifestProvider = { ProotHandshakeManifest.build(targetContext) },
                callerVerifier = { true },
            )
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
            data.writeInt(99)
            val handled = binder.transact(ProotRuntimeProtocol.TX_HANDSHAKE, data, reply, 0)
            assertTrue(handled)
            // The protocol carries no cross-APK exceptions: consume the status word,
            // then the server's unsupported-protocol status byte (which the client
            // maps to PROTOCOL_MISMATCH).
            reply.readException()
            assertEquals(
                ProotRuntimeProtocol.REPLY_UNSUPPORTED_PROTOCOL.toInt(),
                reply.readByte().toInt(),
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun aCallerVerifierRejectionAnswersCallerMismatchNotACrash() {
        val binder =
            ProotRuntimeServiceBinder(
                manifestProvider = { ProotHandshakeManifest.build(targetContext) },
                callerVerifier = { false },
            )
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
            data.writeInt(ProotRuntimeProtocol.PROTOCOL_VERSION)
            val handled = binder.transact(ProotRuntimeProtocol.TX_HANDSHAKE, data, reply, 0)
            assertTrue(handled)
            // The protocol carries no cross-APK exceptions: the rejection is a
            // stable status byte (which the client maps to SIGNATURE_MISMATCH).
            reply.readException()
            assertEquals(
                ProotRuntimeProtocol.REPLY_CALLER_MISMATCH.toInt(),
                reply.readByte().toInt(),
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
        // And the client-side mapping of the same byte.
        val clientBinder =
            ProotRuntimeServiceBinder(
                manifestProvider = { ProotHandshakeManifest.build(targetContext) },
                callerVerifier = { false },
            )
        val outcome = ProotHandshakeClient.handshake(clientBinder)
        assertEquals(UnavailableCause.SIGNATURE_MISMATCH, (outcome as ProotHandshakeClient.Outcome.Failed).cause)
    }

    @Test
    fun anUnknownTransactionCodeIsIgnored() {
        val binder =
            ProotRuntimeServiceBinder(
                manifestProvider = { ProotHandshakeManifest.build(targetContext) },
                callerVerifier = { true },
            )
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.helix.runtime.proot.IUnknown/1")
            val handled = binder.transact(9999, data, reply, 0)
            assertFalse("unknown codes fall through to the base Binder (unhandled)", handled)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
