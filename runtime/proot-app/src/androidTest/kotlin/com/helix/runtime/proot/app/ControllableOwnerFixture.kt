package com.helix.runtime.proot.app

import android.os.IBinder
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Controlled Binder callbacks; real cross-process death has its own SIGKILL suite. */
internal class ControllableOwnerFixture {
    val links = AtomicInteger()
    val unlinks = AtomicInteger()
    val recipient = AtomicReference<IBinder.DeathRecipient?>()
    val binder: IBinder =
        Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
        ) { _, method, args ->
            when (method.name) {
                "linkToDeath" -> {
                    check(recipient.compareAndSet(null, args!![0] as IBinder.DeathRecipient))
                    links.incrementAndGet()
                    null
                }

                "unlinkToDeath" -> {
                    check(recipient.compareAndSet(args!![0] as IBinder.DeathRecipient, null))
                    unlinks.incrementAndGet()
                    true
                }

                else -> {
                    error("Unexpected Binder operation: ${method.name}")
                }
            }
        } as IBinder

    fun die() {
        recipient.get()?.binderDied()
    }
}
