package com.helix.app.maintenance

/** A later OS boot proves old local processes stopped; it does not prove their command outcomes. */
internal class PreviewBootWitness(
    private val boot: () -> Int?,
    private val read: (String) -> Int?,
    private val write: (String, Int) -> Unit,
) {
    fun provesStopped(fingerprint: String): Boolean {
        val current = boot()?.takeIf { it >= 0 } ?: return false
        val previous = read(fingerprint)
        if (previous == null) {
            write(fingerprint, current)
        }
        return previous != null && previous >= 0 && current > previous
    }
}
