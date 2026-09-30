package com.helix.runtime.cli.client

/** A syntactically valid receipt may still belong to another job or request. */
internal object CliJobIdentity {
    fun matches(
        record: CliModelJobRecord,
        jobId: String,
        requestHash: String?,
    ): Boolean =
        record.jobId == jobId && (requestHash == null || record.requestSha256 == requestHash.substringBefore(':'))
}
