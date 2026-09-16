package com.helix.app

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** One safely published result; callers can wait, but can never execute the factory themselves. */
internal class BackgroundInitialization<T>(
    name: String,
    factory: () -> T,
) {
    private val task = FutureTask(factory)
    private val worker = Thread(task, name)

    init {
        worker.start()
    }

    fun await(): T =
        try {
            task.get()
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        }
}
