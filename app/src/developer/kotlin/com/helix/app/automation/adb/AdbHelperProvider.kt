package com.helix.app.automation.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.helix.app.automation.AutomationModule
import com.helix.extensions.mobileuse.automation.backend.AdbAutomationBackend

/** Computer bootstrap, bound to one authorized ADB process. Never grants a conversation scope. */
class AdbHelperProvider : ContentProvider() {
    private val hostLifetime = Binder()
    private val tickets =
        AdbLaunchTickets<IBinder>(android.os.SystemClock::elapsedRealtime) {
            ByteArray(32)
                .also(java.security.SecureRandom()::nextBytes)
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }

    private data class PendingDeath(
        val identity: AdbLaunchTickets.Identity,
        val token: IBinder,
        val death: IBinder.DeathRecipient,
    )

    private var pendingDeath: PendingDeath? = null

    override fun onCreate() = true

    @Synchronized
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        require(method in setOf("begin", "attach"))
        check(android.os.Build.VERSION.SDK_INT >= 31) { "Computer helper requires Android 12 or newer" }
        val app = requireNotNull(context)
        require(Binder.getCallingUid() == 2000 && Binder.getCallingPid() > 0)
        require(
            AutomationModule.computerAdbBackend?.state() !=
                com.helix.extensions.mobileuse.automation.AutomationBackendState.READY,
        ) { "ADB_HELPER_ALREADY_CONNECTED" }
        val fields = requireNotNull(extras)
        val launch = requireNotNull(arg).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val apk = requireNotNull(fields.getString("apk"))
        val ownerUid = fields.getInt("ownerUid")
        require(
            apk == app.applicationInfo.sourceDir && ownerUid == app.applicationInfo.uid,
        ) { "ADB_HELPER_APK_CHANGED" }
        val identity = AdbLaunchTickets.Identity(launch, Binder.getCallingPid(), ownerUid, apk)
        if (method == "begin") return begin(identity, fields)
        require(fields.keySet() == setOf("apk", "ownerUid", "nonce", "service"))
        val nonce = requireNotNull(fields.getString("nonce"))
        require(nonce.matches(Regex("[a-f0-9]{64}")))
        val remote = requireNotNull(fields.getBinder("service"))
        tickets.verify(identity, nonce)
        check(AdbHelperProtocol.identity(remote, nonce) == Binder.getCallingPid()) { "ADB_HELPER_BINDER_CHANGED" }
        lateinit var backend: AdbAutomationBackend
        backend = AdbAutomationBackend(remote) { AutomationModule.computerAdbBackend === backend }
        remote.linkToDeath({
            synchronized(this) {
                if (AutomationModule.computerAdbBackend === backend) AutomationModule.computerAdbBackend = null
            }
        }, 0)
        check(remote.isBinderAlive)
        tickets.consume(identity, nonce)
        pendingDeath?.let { it.token.unlinkToDeath(it.death, 0) }
        pendingDeath = null
        AutomationModule.computerAdbBackend = backend
        return Bundle().apply {
            putString("state", "connected")
            putBinder("owner", hostLifetime)
        }
    }

    private fun begin(
        identity: AdbLaunchTickets.Identity,
        fields: Bundle,
    ): Bundle {
        require(fields.keySet() == setOf("apk", "ownerUid", "lifetime"))
        val token = requireNotNull(fields.getBinder("lifetime"))
        check(token.isBinderAlive) { "ADB_HELPER_EXITED" }
        val nonce = tickets.begin(identity, token)
        if (pendingDeath?.let { it.identity == identity && it.token == token } != true) {
            pendingDeath?.let { it.token.unlinkToDeath(it.death, 0) }
            val death =
                IBinder.DeathRecipient {
                    synchronized(this) {
                        tickets.cancel(identity, token)
                        if (pendingDeath?.token == token) pendingDeath = null
                    }
                }
            pendingDeath = PendingDeath(identity, token, death)
            try {
                token.linkToDeath(death, 0)
                check(token.isBinderAlive) { "ADB_HELPER_EXITED" }
            } catch (failure: android.os.RemoteException) {
                tickets.cancel(identity, token)
                pendingDeath = null
                throw failure
            }
        }
        return Bundle().apply {
            putString("nonce", nonce)
            putBinder("owner", hostLifetime)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = error("Unsupported")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = error("Unsupported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = error("Unsupported")
}

internal object AdbHelperProtocol {
    const val DESCRIPTOR = "com.helix.agent.ComputerAdb"
    const val IDENTITY = IBinder.FIRST_CALL_TRANSACTION + 200

    fun identity(
        binder: IBinder,
        nonce: String,
    ): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(nonce)
            check(binder.transact(IDENTITY, data, reply, 0))
            reply.readException()
            reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
