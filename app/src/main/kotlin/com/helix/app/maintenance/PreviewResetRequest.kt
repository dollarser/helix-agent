package com.helix.app.maintenance

import java.util.concurrent.atomic.AtomicBoolean

internal enum class PreviewResetSection { DATABASE, TERMINAL, MOBILE_USE }

internal data class PreviewResetStep(
    val section: PreviewResetSection,
    val verifyUnchanged: () -> Unit,
    val reset: () -> Unit,
)

/** A frozen set of effects. Discovery/reopening never executes it; failed attempts need a fresh review. */
internal class PreviewResetRequest(
    private val steps: List<PreviewResetStep>,
    val requiresRestart: Boolean = false,
    private val verifyQuiescent: () -> Unit = {},
) : IllegalStateException("Preview state requires explicit reset") {
    private val consumed = AtomicBoolean()
    val sections = steps.map { it.section }.toSet()

    fun confirm(beforeDatabaseReset: () -> Unit = {}) {
        check(!requiresRestart) { "Old executions require verified quiescence" }
        check(consumed.compareAndSet(false, true)) { "Reset confirmation already used" }
        verifyQuiescent()
        steps.forEach { it.verifyUnchanged() }
        if (PreviewResetSection.DATABASE in sections) beforeDatabaseReset()
        verifyQuiescent()
        steps.forEach { it.verifyUnchanged() }
        steps.forEach { step ->
            verifyQuiescent()
            step.verifyUnchanged()
            step.reset()
        }
    }
}
