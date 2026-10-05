package com.helix.app.eval

import android.content.Context
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.config.MobileUseGrantStore

/** Temporarily configure an owned fixture scope, restoring the user's exact global record. */
internal class MobileUseGlobalScopeFixture(
    context: Context,
) : AutoCloseable {
    private val lines = PrefsLineStore(context, "helix-mobile-use", synchronous = true)
    private val key = MobileUseGrantStore.CONFIG_KEY
    private val original = lines.lines(key)

    init {
        MobileUseGrantStore(lines::lines, lines::setLines, { null }).configureGlobal(emptySet(), true)
    }

    fun configure(
        packages: Set<String>,
        wholePhone: Boolean,
    ) {
        MobileUseGrantStore(lines::lines, lines::setLines, { null }).configureGlobal(packages, wholePhone)
    }

    override fun close() {
        lines.setLines(key, original)
    }
}
