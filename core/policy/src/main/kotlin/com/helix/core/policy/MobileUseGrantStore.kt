package com.helix.core.policy

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** User configuration, not an Accessibility connection or an in-flight operation. */
data class MobileUseGrant(
    val conversationId: String,
    val scope: AutomationSessionScope,
    val shareScreens: Boolean,
) {
    init {
        require(conversationId.isNotBlank() && conversationId.none(Char::isISOControl))
        require(scope.grantId.isNotBlank() && scope.expiresAt == Instant.MAX && scope.maxActions == 0)
    }
}

/** Uses the host's synchronous settings storage. Only explicit user changes write these records. */
class MobileUseGrantStore(
    private val read: (String) -> List<String>,
    private val write: (String, List<String>) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val failedWrites = mutableSetOf<String>()

    @Synchronized
    fun find(conversationId: String): MobileUseGrant? {
        val fields =
            if (GLOBAL_ID in failedWrites ||
                conversationId in failedWrites
            ) {
                emptyList()
            } else {
                read(key(conversationId))
            }
        if (fields.isEmpty()) return null
        check(fields.size == 4 && fields[0] == VERSION && fields[1] == conversationId) {
            "Mobile Use configuration is unreadable"
        }
        val scope = UserScopeCodec.decode(fields[2]) as? AutomationSessionScope
        val saved =
            MobileUseGrant(
                conversationId,
                checkNotNull(scope) { "Mobile Use scope is unreadable" },
                checkNotNull(fields[3].toBooleanStrictOrNull()) { "Mobile Use sharing setting is unreadable" },
            )
        val global = if (conversationId == GLOBAL_ID) null else globalConfiguration()
        return if (global == null) saved else effectiveGrant(saved, global)
    }

    @Synchronized
    fun globalConfiguration(): MobileUseGrant? = find(GLOBAL_ID)

    /** Explicit plugin-wide scope edits invalidate existing observation and approval identities. */
    @Synchronized
    fun configureGlobal(
        applications: Set<String>,
        wholePhone: Boolean,
    ): MobileUseGrant = authorize(GLOBAL_ID, applications, wholePhone)

    @Synchronized
    fun enableFromGlobal(conversationId: String) {
        val global = checkNotNull(globalConfiguration()) { "MOBILE_USE_NOT_CONFIGURED" }
        authorize(conversationId, global.scope.allowedPackages, global.scope.allApplications, global.shareScreens)
    }

    @Synchronized
    fun authorize(
        conversationId: String,
        applications: Set<String>,
        wholePhone: Boolean,
        shareScreens: Boolean = true,
    ): MobileUseGrant {
        val existing = find(conversationId)
        val selected = if (wholePhone) emptySet() else applications.toSortedSet()
        existing
            ?.takeIf {
                it.scope.allowedPackages == selected && it.scope.allApplications == wholePhone &&
                    it.shareScreens == shareScreens
            }?.let { return it }
        val grant =
            MobileUseGrant(
                conversationId,
                AutomationSessionScope(selected, emptySet(), 0, Instant.MAX, wholePhone, newId()),
                shareScreens,
            )
        persist(conversationId, listOf(VERSION, conversationId, UserScopeCodec.encode(grant.scope), "$shareScreens"))
        return checkNotNull(find(conversationId))
    }

    @Synchronized
    fun revoke(conversationId: String) {
        persist(conversationId, emptyList())
    }

    /** Approval binding must still match when execution actually starts, including after a scope edit. */
    fun matches(
        conversationId: String?,
        scopeRef: String?,
    ): Boolean = conversationId != null && scopeRef != null && find(conversationId)?.scope?.toScopeRef() == scopeRef

    @Synchronized
    fun revokeIfMatching(
        conversationId: String,
        scopeRef: String,
    ): Boolean {
        if (!matches(conversationId, scopeRef)) return false
        revoke(conversationId)
        return true
    }

    private fun persist(
        conversationId: String,
        fields: List<String>,
    ) {
        failedWrites.add(conversationId)
        write(key(conversationId), fields)
        failedWrites.remove(conversationId)
    }

    private fun key(conversationId: String): String {
        require(conversationId.isNotBlank() && conversationId.none(Char::isISOControl))
        val hash = MessageDigest.getInstance("SHA-256").digest(conversationId.toByteArray(Charsets.UTF_8))
        return "mobile-use-conversation-" + hash.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val VERSION = "mobile-use-conversation-v1"
        const val GLOBAL_ID = "plugin:mobile-use:global"
    }
}

private fun effectiveGrant(
    saved: MobileUseGrant,
    global: MobileUseGrant,
): MobileUseGrant {
    val identity =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${saved.scope.grantId}:${global.scope.grantId}:${saved.conversationId}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    return saved.copy(scope = global.scope.copy(grantId = identity), shareScreens = global.shareScreens)
}
