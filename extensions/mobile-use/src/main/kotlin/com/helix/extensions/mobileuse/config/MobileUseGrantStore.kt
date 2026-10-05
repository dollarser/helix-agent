package com.helix.extensions.mobileuse.config

import com.helix.core.policy.AutomationSessionScope
import com.helix.core.policy.UserScopeCodec
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Derived execution scope, never a second persistent conversation selection. */
data class MobileUseGrant(
    val conversationId: String,
    val scope: AutomationSessionScope,
    val shareScreens: Boolean,
)

/** Only global plugin configuration is stored here. The host owns durable session selection. */
class MobileUseGrantStore(
    private val read: (String) -> List<String>,
    private val write: (String, List<String>) -> Unit,
    private val selectionId: (String) -> String?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var failedWrite = false

    fun find(conversationId: String): MobileUseGrant? {
        val selection = selectionId(conversationId) ?: return null
        return globalConfiguration()?.let { global ->
            val identity =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("$conversationId:$selection:${global.scope.grantId}".toByteArray())
                    .joinToString("") { "%02x".format(it) }
            global.copy(conversationId = conversationId, scope = global.scope.copy(grantId = identity))
        }
    }

    @Synchronized
    fun globalConfiguration(): MobileUseGrant? {
        if (failedWrite) return null
        return read(CONFIG_KEY).takeIf { it.isNotEmpty() }?.let { fields ->
            check(fields.size == 3 && fields[0] == VERSION) { "Mobile Use configuration is unreadable" }
            val scope = checkNotNull(UserScopeCodec.decode(fields[1]) as? AutomationSessionScope)
            check(scope.grantId.isNotBlank() && scope.expiresAt == Instant.MAX && scope.maxActions == 0)
            MobileUseGrant("plugin:mobile-use:global", scope, checkNotNull(fields[2].toBooleanStrictOrNull()))
        }
    }

    @Synchronized
    fun configureGlobal(
        applications: Set<String>,
        wholePhone: Boolean,
        shareScreens: Boolean = true,
    ): MobileUseGrant {
        val selected = if (wholePhone) emptySet() else applications.toSortedSet()
        globalConfiguration()
            ?.takeIf {
                it.scope.allowedPackages == selected && it.scope.allApplications == wholePhone &&
                    it.shareScreens == shareScreens
            }?.let { return it }
        val scope = AutomationSessionScope(selected, emptySet(), 0, Instant.MAX, wholePhone, newId())
        failedWrite = true
        write(CONFIG_KEY, listOf(VERSION, UserScopeCodec.encode(scope), "$shareScreens"))
        failedWrite = false
        return checkNotNull(globalConfiguration())
    }

    fun matches(
        conversationId: String?,
        scopeRef: String?,
    ): Boolean = conversationId != null && scopeRef != null && find(conversationId)?.scope?.toScopeRef() == scopeRef

    companion object {
        const val CONFIG_KEY = "mobile-use-global-configuration"
        private const val VERSION = "mobile-use-config-v2"
    }
}
