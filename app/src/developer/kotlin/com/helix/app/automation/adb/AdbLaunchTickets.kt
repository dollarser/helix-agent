package com.helix.app.automation.adb

/** Authorized ADB is the trust root. A ticket binds a launch; it does not attest shell code. */
internal class AdbLaunchTickets<T>(
    private val now: () -> Long,
    private val nonce: () -> String,
) {
    data class Identity(
        val launch: String,
        val pid: Int,
        val ownerUid: Int,
        val apk: String,
    )

    private data class Ticket<T>(
        val identity: Identity,
        val lifetime: T,
        val nonce: String,
        val expires: Long,
    )

    private var pending: Ticket<T>? = null

    @Synchronized
    fun begin(
        identity: Identity,
        lifetime: T,
    ): String {
        require(identity.launch.matches(Regex("[a-f0-9]{32}")) && identity.pid > 0 && identity.ownerUid >= 10000)
        require(identity.apk.startsWith("/") && identity.apk.endsWith(".apk"))
        val old = pending
        if (old != null && now() < old.expires) {
            check(old.identity == identity && old.lifetime == lifetime) { "ADB_LAUNCH_PENDING" }
            return old.nonce
        }
        val value = nonce().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        pending = Ticket(identity, lifetime, value, now() + 30_000)
        return value
    }

    @Synchronized
    fun verify(
        identity: Identity,
        nonce: String,
    ) {
        val ticket = checkNotNull(pending) { "ADB_LAUNCH_UNKNOWN" }
        check(now() < ticket.expires) { "ADB_LAUNCH_EXPIRED" }
        check(
            ticket.identity == identity &&
                java.security.MessageDigest.isEqual(ticket.nonce.toByteArray(), nonce.toByteArray()),
        ) { "ADB_LAUNCH_CHANGED" }
    }

    @Synchronized
    fun consume(
        identity: Identity,
        nonce: String,
    ) {
        verify(identity, nonce)
        pending = null
    }

    @Synchronized
    fun cancel(
        identity: Identity,
        lifetime: T,
    ) {
        if (pending?.let { it.identity == identity && it.lifetime == lifetime } == true) pending = null
    }
}
