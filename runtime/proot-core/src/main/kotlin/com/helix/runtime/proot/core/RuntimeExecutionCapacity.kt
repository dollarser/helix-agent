package com.helix.runtime.proot.core

/** Physical capacity and environment maintenance, not a lock on user files or results. */
class RuntimeExecutionCapacity(
    private val maxJobs: Int = 4,
    private val maxTerminals: Int = 2,
) {
    init {
        require(maxJobs in 1..4 && maxTerminals in 1..2)
    }

    private val jobs = mutableMapOf<String, Boolean>()
    private val terminals = mutableSetOf<String>()
    private var maintenance: String? = null

    @Synchronized
    fun reserveJob(id: String): Boolean {
        require(id.isNotBlank())
        if (maintenance != null || id in jobs || jobs.size >= maxJobs) return false
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
    fun reserveTerminal(id: String): Boolean {
        require(id.isNotBlank())
        return maintenance == null && terminals.size < maxTerminals && terminals.add(id)
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
}
