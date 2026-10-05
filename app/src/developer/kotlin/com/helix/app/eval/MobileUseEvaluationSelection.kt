package com.helix.app.eval

import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.config.MobileUseGrantStore

/** Explicit developer evaluation setup; restores global configuration and only its owned selections. */
class MobileUseEvaluationSelection(
    app: HelixApplication,
) : AutoCloseable {
    private val catalog = app.appContainer.pluginService.catalog
    private val record =
        app.appContainer.pluginService
            .list()
            .single { it.native?.pluginId == "mobile-use" }
    private val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
    private val original = lines.lines(MobileUseGrantStore.CONFIG_KEY)
    private val selected = mutableSetOf<String>()
    private val config =
        MobileUseGrantStore(lines::lines, lines::setLines, { session -> catalog.selectionId(session, "mobile-use") })

    fun select(
        session: String,
        packages: Set<String>,
        wholePhone: Boolean,
    ): Boolean {
        config.configureGlobal(packages, wholePhone)
        if (record.id !in catalog.selected(session)) selected.add(session)
        catalog.select(session, record.id, true)
        return true
    }

    override fun close() {
        selected.forEach { catalog.select(it, record.id, false) }
        lines.setLines(MobileUseGrantStore.CONFIG_KEY, original)
    }
}
