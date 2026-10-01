package com.helix.core.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local execution ownership; never serialized into a Provider request. */
class LocalModelCallContext(
    val turnId: String,
    val modelCallId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalModelCallContext>
}
