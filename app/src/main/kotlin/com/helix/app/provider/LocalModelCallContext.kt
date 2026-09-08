package com.helix.app.provider

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local execution ownership; never serialized into a Provider request. */
internal class LocalModelCallContext(
    val turnId: String,
    val modelCallId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalModelCallContext>
}
