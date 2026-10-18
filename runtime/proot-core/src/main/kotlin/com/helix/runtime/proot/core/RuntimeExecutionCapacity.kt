package com.helix.runtime.proot.core

/** Physical capacity and environment maintenance, not a lock on user files or results. */
class RuntimeExecutionCapacity(
    private val maxJobs: Int = MAX_JOBS,
    private val maxTerminals: Int = MAX_TERMINALS,
    private val maxTotal: Int = MAX_TOTAL,
) {
    init {
        require(maxJobs in 1..MAX_JOBS && maxTerminals in 1..MAX_TERMINALS && maxTotal in 1..MAX_TOTAL)
    }

    private val jobs = mutableMapOf<String, Boolean>()
    private val terminals = mutableSetOf<String>()
    private var maintenance: String? = null

    @Synchronized
    fun reserveJob(id: String): Boolean {
        require(id.isNotBlank())
        val known = id in jobs || id in terminals
        val unavailable = maintenance != null || jobs.size >= maxJobs || full()
        if (known || unavailable) return false
        jobs[id] = false
        return true
    }

    @Synchronized
    fun beginJob(
        id: String,
        reserved: Boolean,
    ): Boolean {
        val available = maintenance == null && if (reserved) jobs[id] == false else reserveJob(id)
        if (available) jobs[id] = true
        return available
    }

    /** Submission refused before handoff. Never frees a job which is still physically running. */
    @Synchronized
    fun releaseReservation(id: String) {
        if (jobs[id] == false) jobs.remove(id)
    }

    /** Terminal metadata does not prove physical cleanup; foreground ownership follows this fact. */
    @Synchronized
    fun isJobRunning(id: String): Boolean = jobs[id] == true

    /** Called from the original executor's finally after process/pump cleanup, not from query/ACK. */
    @Synchronized
    fun finishJob(id: String) {
        jobs.remove(id)
    }

    @Synchronized
    fun reserveTerminal(
        id: String,
        allowance: Int = maxTerminals,
    ): Boolean {
        require(id.isNotBlank())
        require(allowance in 0..MAX_TERMINALS)
        return maintenance == null && id !in jobs && terminals.size < minOf(maxTerminals, allowance) &&
            !full() && terminals.add(id)
    }

    @Synchronized
    fun releaseTerminal(id: String) {
        terminals.remove(id)
    }

    @Synchronized
    fun reserveMaintenance(id: String): Boolean {
        if (maintenance != null || jobs.isNotEmpty() || terminals.isNotEmpty()) return false
        maintenance = id
        return true
    }

    @Synchronized
    fun releaseMaintenance(id: String) {
        if (maintenance == id) maintenance = null
    }

    private fun full(): Boolean = jobs.size + terminals.size >= maxTotal

    companion object {
        const val MAX_JOBS = 4
        const val MAX_TERMINALS = 4

        // Retain the previous aggregate budget (four jobs + two terminals), shared by both kinds.
        const val MAX_TOTAL = 6
    }
}
