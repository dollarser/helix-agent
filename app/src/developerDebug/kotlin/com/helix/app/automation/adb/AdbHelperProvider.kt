package com.helix.app.automation.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.helix.app.automation.AutomationModule
import com.helix.extensions.mobileuse.automation.backend.AdbAutomationBackend
import org.json.JSONObject
import java.io.File

/** Debug-only computer bootstrap. Connection does not select a plugin or grant a conversation scope. */
class AdbHelperProvider : ContentProvider() {
    private val hostLifetime = Binder()

    override fun onCreate() = true

    @Synchronized
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        require(method == "attach")
        val app = requireNotNull(context)
        require(Binder.getCallingUid() == 2000 && Binder.getCallingPid() > 0)
        require(
            AutomationModule.computerAdbBackend?.state() !=
                com.helix.extensions.mobileuse.automation.AutomationBackendState.READY,
        ) { "ADB_HELPER_ALREADY_CONNECTED" }
        val fields = requireNotNull(extras)
        val launch = requireNotNull(arg).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val file = File(app.filesDir, "adb-helper/$launch.json")
        require(
            file.isFile && file.length() in 1..4096 &&
                !java.nio.file.Files
                    .isSymbolicLink(file.toPath()),
        )
        val grant = JSONObject(file.readText())
        require(grant.getInt("pid") == Binder.getCallingPid()) { "ADB_HELPER_PID_CHANGED" }
        require(grant.getString("apk") == app.applicationInfo.sourceDir) { "ADB_HELPER_APK_CHANGED" }
        require(
            SystemClock.elapsedRealtime() - grant.getLong("issuedAtMillis") in 0..60_000,
        ) { "ADB_HELPER_LAUNCH_EXPIRED" }
        val nonce = requireNotNull(fields.getString("nonce"))
        require(
            nonce.length == 64 &&
                java.security.MessageDigest.isEqual(nonce.toByteArray(), grant.getString("nonce").toByteArray()),
        )
        val remote = requireNotNull(fields.getBinder("service"))
        check(AdbHelperProtocol.identity(remote, nonce) == Binder.getCallingPid()) { "ADB_HELPER_BINDER_CHANGED" }
        lateinit var backend: AdbAutomationBackend
        backend = AdbAutomationBackend(remote) { AutomationModule.computerAdbBackend === backend }
        remote.linkToDeath({
            synchronized(this) {
                if (AutomationModule.computerAdbBackend === backend) AutomationModule.computerAdbBackend = null
            }
        }, 0)
        check(remote.isBinderAlive)
        check(file.delete())
        AutomationModule.computerAdbBackend = backend
        return Bundle().apply { putBinder("owner", hostLifetime) }
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
