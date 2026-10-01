package com.helix.app.chat

import com.helix.runtime.quickjs.JsExecutionClient
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Observe only a durable interrupted native owner; ordinary app startup never executes code. */
internal class NativeJavascriptRecovery(
    private val ownership: ExecutionOwnership,
    private val client: JsExecutionClient,
    scope: CoroutineScope,
) {
    private val native = NativeJavascriptOwnership(ownership)
    private val observer = AutomaticRuntimeCollection(CoroutineScope(scope.coroutineContext + Dispatchers.IO))

    fun observe() {
        val owner =
            runCatching { native.interruptedOwner() }
                .onFailure { android.util.Log.e("NativeRecovery", "Admission journal unreadable; execution blocked") }
                .getOrNull() ?: return
        observer.request("${owner.executionId}:${owner.generation}") {
            if (!ownership.isRetained(owner) || native.recover(owner, client::retireNativeHost)) {
                AutomaticRuntimeCollection.Observation.COMPLETE
            } else {
                AutomaticRuntimeCollection.Observation.RETRY
            }
        }
    }
}
