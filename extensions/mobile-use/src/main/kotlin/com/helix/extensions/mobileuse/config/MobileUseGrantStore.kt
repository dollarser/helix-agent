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
    val backgroundDisplay: Boolean = false,
    val allowMoveFromMain: Boolean = false,
)

class MobileUseConfigurationUnreadable : IllegalStateException("Mobile Use configuration requires reset")

/** Only global plugin configuration is stored here. The host owns durable session selection. */
class MobileUseGrantStore(
    private val read: (String) -> List<String>,
    private val write: (String, List<String>) -> Unit,
    private val selectionId: (String) -> String?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val defaultBackground: () -> Boolean = { false },
) {
    private var failedWrite = false
    private val initialExecution by lazy { defaultBackground() to false }

    fun find(conversationId: String): MobileUseGrant? {
        val selection = selectionId(conversationId) ?: return null
        return globalConfiguration()?.let { global ->
            val identity =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        (
                            "$conversationId:$selection:${global.scope.grantId}:" +
                                "${global.backgroundDisplay}:${global.allowMoveFromMain}"
                        ).toByteArray(),
                    ).joinToString("") { "%02x".format(it) }
            global.copy(conversationId = conversationId, scope = global.scope.copy(grantId = identity))
        }
    }

    @Synchronized
    @Suppress("ThrowsCount") // Reject malformed fields without accepting a partial or broader grant.
    fun globalConfiguration(): MobileUseGrant? {
        if (failedWrite) return null
        return read(CONFIG_KEY).takeIf { it.isNotEmpty() }?.let { fields ->
            if (fields.size != 3 || fields[0] != VERSION) throw MobileUseConfigurationUnreadable()
            val scope =
                UserScopeCodec.decode(fields[1]) as? AutomationSessionScope
                    ?: throw MobileUseConfigurationUnreadable()
            if (scope.grantId.isBlank() || scope.expiresAt != Instant.MAX || scope.maxActions != 0) {
                throw MobileUseConfigurationUnreadable()
            }
            val execution = executionSettings()
            MobileUseGrant(
                "plugin:mobile-use:global",
                scope,
                fields[2].toBooleanStrictOrNull() ?: throw MobileUseConfigurationUnreadable(),
                execution.first,
                execution.second,
            )
        }
    }

    @Synchronized
    fun configureGlobal(
        applications: Set<String>,
        wholePhone: Boolean,
        shareScreens: Boolean = true,
    ): MobileUseGrant {
        if (read(EXECUTION_KEY).isEmpty()) configureExecution(initialExecution.first, initialExecution.second)
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

    @Synchronized
    fun executionSettings(): Pair<Boolean, Boolean> {
        val fields = read(EXECUTION_KEY)
        if (fields.isEmpty()) return initialExecution
        if (fields.size != 2 || fields.any { it.toBooleanStrictOrNull() == null }) {
            throw MobileUseConfigurationUnreadable()
        }
        return fields[0].toBooleanStrict() to fields[1].toBooleanStrict()
    }

    @Synchronized
    fun configureExecution(
        background: Boolean,
        allowMoveFromMain: Boolean,
    ) {
        failedWrite = true
        write(EXECUTION_KEY, listOf(background.toString(), allowMoveFromMain.toString()))
        failedWrite = false
        check(executionSettings() == (background to allowMoveFromMain))
    }

    fun matches(
        conversationId: String?,
        scopeRef: String?,
    ): Boolean = conversationId != null && scopeRef != null && find(conversationId)?.scope?.toScopeRef() == scopeRef

    companion object {
        const val EXECUTION_KEY = "mobile-use-execution-display"
        const val CONFIG_KEY = "mobile-use-global-configuration"
        private const val VERSION = "mobile-use-config-v1"
    }
}
