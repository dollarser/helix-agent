package com.helix.runtime.cli.app

internal object CodexSmokeJobProbe {
    fun run(
        runner: CodexModelJobRunner,
        jobId: String,
        requestHash: String,
    ): CodexModelJobRecord {
        val rejection =
            when (runner.submit(jobId, requestHash)) {
                is CodexModelJobSubmit.Accepted, is CodexModelJobSubmit.Duplicate -> null
                CodexModelJobSubmit.Busy -> "job-busy"
                CodexModelJobSubmit.JournalFull -> "job-journal-full"
                CodexModelJobSubmit.RequestMismatch -> "job-request-mismatch"
            }
        if (rejection != null) throw CodexSmokeException(rejection)
        return awaitSmokeJob(runner, jobId)
    }

    private fun awaitSmokeJob(
        runner: CodexModelJobRunner,
        jobId: String,
    ): CodexModelJobRecord {
        repeat(480) {
            runner.query(jobId)?.takeIf { it.state.terminal }?.let { return it }
            Thread.sleep(250)
        }
        runner.cancel(jobId)
        return runner.query(jobId) ?: throw CodexSmokeException("job-missing")
    }
}
