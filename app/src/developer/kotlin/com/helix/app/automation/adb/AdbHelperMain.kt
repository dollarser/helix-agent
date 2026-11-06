package com.helix.app.automation.adb

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import com.helix.extensions.mobileuse.automation.backend.ShizukuUiUserService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Computer ADB starts the installed Advanced APK. No pairing, arbitrary shell API or restart loop. */
@Keep
object AdbHelperMain {
    private var ownerConnection: IBinder? = null
    private var startupLifetime: IBinder? = null

    @JvmStatic
    @Keep
    // Preserve the original failure while making standalone startup errors visible to the computer.
    @Suppress("TooGenericExceptionCaught")
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (failure: Exception) {
            var detail: Throwable = failure
            while (detail is java.lang.reflect.InvocationTargetException) detail = detail.targetException
            System.err.println("HELIX_ADB_FAILED ${detail.javaClass.simpleName}: ${detail.message}")
            kotlin.system.exitProcess(1)
        }
    }

    // Standalone shell startup has no public ActivityThread bootstrap.
    @android.annotation.SuppressLint("PrivateApi")
    @Suppress("DEPRECATION")
    private fun run(args: Array<String>) {
        require(Process.myUid() == 2000 && args.size == 2)
        check(android.os.Build.VERSION.SDK_INT >= 31) { "Computer helper requires Android 12 or newer" }
        val packageName = args[0].also { require(it == "com.helix.agent.developer") }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val thread = requireNotNull(Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null))
        val system = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        val shell = shellContext(system, thread)
        val ownerInfo = shell.packageManager.getApplicationInfo(packageName, 0)
        val app =
            object : android.content.ContextWrapper(shell) {
                override fun getApplicationInfo() = ownerInfo

                override fun getPackageName() = packageName
            }
        val ownerUid = ownerInfo.uid
        println(
            "HELIX_ADB_READY " +
                JSONObject()
                    .put("pid", Process.myPid())
                    .put("uid", Process.myUid())
                    .put("apk", app.applicationInfo.sourceDir)
                    .put("issuedAtMillis", android.os.SystemClock.elapsedRealtime()),
        )
        val bootstrap = readBootstrap(args[1])
        val launch = bootstrap.getString("launch").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val handoff = AdbProcessHandoff(shell, ownerInfo, launch)
        startupLifetime = handoff.lifetime
        val challenge = handoff.call("begin")
        val nonce =
            requireNotNull(challenge.getString("nonce"))
                .also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val owner = requireNotNull(challenge.getBinder("owner"))
        val engine = ShizukuUiUserService(app)
        val stopped = AtomicBoolean()

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                engine.close()
            } finally {
                kotlin.system.exitProcess(0)
            }
        }
        val service = HostProcessBinder(ownerUid, nonce, engine)
        try {
            ownerConnection = owner
            owner.linkToDeath({ stop() }, 0)
            check(owner.isBinderAlive)
            val reply = handoff.call("attach", service, nonce)
            check(reply.getBinder("owner") == owner) { "ADB_HOST_PROCESS_CHANGED" }
            check(reply.getString("state") == "connected") { "ADB_HANDOFF_INCOMPLETE" }
            println("HELIX_ADB_CONNECTED pid=${Process.myPid()}")
            Looper.loop()
            stop()
        } finally {
            if (stopped.compareAndSet(false, true)) engine.close()
        }
    }

    private fun readBootstrap(path: String): JSONObject {
        val startup =
            java.io.File(
                path.also {
                    require(it.matches(Regex("/data/local/tmp/helix-adb-[a-f0-9]{32}/input")))
                },
            )
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!startup.isFile && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
        check(startup.isFile && startup.length() in 1..4096) { "ADB_BOOTSTRAP_TIMEOUT" }
        val bootstrap = JSONObject(startup.readText())
        require(bootstrap.keys().asSequence().toSet() == setOf("launch")) { "ADB_BOOTSTRAP_FIELDS" }
        check(startup.delete())
        return bootstrap
    }

    // Root app context avoids inheriting the system package's attribution; failure is surfaced to the launcher.
    @android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun shellContext(
        system: Context,
        thread: Any,
    ): Context {
        val scoped = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        val implementation = Class.forName("android.app.ContextImpl")
        val loaded = implementation.getDeclaredField("mPackageInfo").apply { isAccessible = true }.get(scoped)
        val create =
            implementation.declaredMethods.single {
                it.name == "createAppContext" &&
                    it.parameterTypes.size == 2
            }
        create.isAccessible = true
        return create.invoke(null, thread, loaded) as Context
    }
}

private class HostProcessBinder(
    private val ownerUid: Int,
    private val nonce: String,
    private val engine: IBinder,
) : Binder() {
    private var ownerPid: Int? = null

    @Synchronized
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        require(getCallingUid() == ownerUid)
        if (code == AdbHelperProtocol.IDENTITY) {
            data.enforceInterface(AdbHelperProtocol.DESCRIPTOR)
            require(data.readString() == nonce && data.dataAvail() == 0)
            check(ownerPid == null || ownerPid == getCallingPid())
            ownerPid = getCallingPid()
            reply?.writeNoException()
            reply?.writeInt(Process.myPid())
            return true
        }
        check(ownerPid == getCallingPid()) { "ADB_HOST_PROCESS_CHANGED" }
        return engine.transact(code, data, reply, flags)
    }
}
