package com.helix.runtime.proot.app

import android.os.IBinder
import android.os.RemoteException
import java.lang.reflect.Proxy

/** Hold death-link registration long enough to expose any job queued before owner registration. */
internal fun delayedDeadOwner(): IBinder =
    Proxy.newProxyInstance(
        IBinder::class.java.classLoader,
        arrayOf(IBinder::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "linkToDeath" -> {
                Thread.sleep(1500)
                throw RemoteException("Owner was already dead")
            }

            "unlinkToDeath" -> {
                error("No death link was installed")
            }

            "isBinderAlive", "pingBinder" -> {
                false
            }

            else -> {
                error("Unexpected Binder operation: ${method.name}")
            }
        }
    } as IBinder
