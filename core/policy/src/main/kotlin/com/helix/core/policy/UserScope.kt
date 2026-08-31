package com.helix.core.policy

import java.time.Duration
import java.time.Instant

/**
 * Explicit user scope of a capability grant (platform capabilities doc section 2). File, browser
 * and UI automation all run inside an explicit scope; there is no implicit "everything" scope.
 *
 * Scopes are user data: the model may request one but never creates, widens or caches it
 * (AGENTS.md; architecture doc 7.1). All scopes are value types with fail-closed construction —
 * a structurally invalid scope cannot be built, so the Policy Engine (HXA-033) never sees one.
 */
sealed interface UserScope {
    /**
     * Canonical, bounded reference stored in `capability_grants.userScopeRef` (architecture doc
     * 9.1). Stable for the same scope value (set members are sorted) and capped at
     * [MAX_SCOPE_REF_LENGTH].
     */
    fun toScopeRef(): String

    companion object {
        const val MAX_SCOPE_REF_LENGTH = 1024
        const val MAX_WORKSPACE_ID_LENGTH = 64
        const val MAX_URI_LENGTH = 1024
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_ROOT_PATH_LENGTH = 512
        const val MAX_PACKAGE_NAME_LENGTH = 255
        const val MAX_TAB_ID_LENGTH = 64
        const val MAX_AUTOMATION_ACTIONS_HARD_CAP = 10_000
        const val MAX_ROOT_SESSION_TTL_MINUTES = 60
    }
}

/** Session-private workspace directory; the default scope for file tools (doc 9 sections 2 and 4.1). */
data class WorkspaceScope(
    val workspaceId: String,
) : UserScope {
    init {
        require(workspaceId.length in 1..UserScope.MAX_WORKSPACE_ID_LENGTH) {
            "workspaceId must be 1..${UserScope.MAX_WORKSPACE_ID_LENGTH} chars: $workspaceId"
        }
        require(IDENTITY_REGEX.matches(workspaceId)) {
            "workspaceId must match [A-Za-z0-9_-]: $workspaceId"
        }
        requireRefLength(buildRef())
    }

    override fun toScopeRef(): String = buildRef()

    private fun buildRef(): String = "workspace:$workspaceId"
}

/** A directory the user selected through SAF and persistently authorized (doc 9 section 2). */
data class DocumentTreeScope(
    val documentTreeUri: String,
    val displayName: String,
) : UserScope {
    init {
        require(documentTreeUri.length in 1..UserScope.MAX_URI_LENGTH) {
            "documentTreeUri must be 1..${UserScope.MAX_URI_LENGTH} chars"
        }
        require(documentTreeUri.startsWith("content://")) {
            "documentTreeUri must be a content:// document URI: $documentTreeUri"
        }
        require(documentTreeUri.contains("/tree/")) {
            "documentTreeUri must be a SAF tree URI (content://.../tree/...): $documentTreeUri"
        }
        require(documentTreeUri.substringAfter("/tree/").isNotBlank()) {
            "documentTreeUri must name a tree document: $documentTreeUri"
        }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName.length <= UserScope.MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be at most ${UserScope.MAX_DISPLAY_NAME_LENGTH} chars"
        }
        requireRefLength(buildRef())
    }

    override fun toScopeRef(): String = buildRef()

    private fun buildRef(): String = "saf-tree:$documentTreeUri"
}

/**
 * Root directories the user chose inside Helix after enabling All files access (doc 9 sections 2
 * and 4.1). Never defaults to the whole shared storage; roots must already be canonical.
 */
data class SharedStorageScope(
    val roots: List<String>,
) : UserScope {
    init {
        require(roots.isNotEmpty()) { "roots must not be empty" }
        val seen = HashSet<String>()
        roots.forEach { root ->
            require(seen.add(root)) { "duplicate root: $root" }
            requireCanonicalRoot(root)
        }
        requireRefLength(buildRef())
    }

    override fun toScopeRef(): String = buildRef()

    private fun buildRef(): String = "allfiles:" + roots.sorted().joinToString(";")
}

/** One Helix-managed browser tab and its current navigation generation (doc 9 section 2). */
data class BrowserTabScope(
    val tabId: String,
    val navigationGeneration: Int,
) : UserScope {
    init {
        require(tabId.length in 1..UserScope.MAX_TAB_ID_LENGTH) {
            "tabId must be 1..${UserScope.MAX_TAB_ID_LENGTH} chars: $tabId"
        }
        require(IDENTITY_REGEX.matches(tabId)) { "tabId must match [A-Za-z0-9_-]: $tabId" }
        require(navigationGeneration >= 0) { "navigationGeneration must be >= 0: $navigationGeneration" }
        requireRefLength(buildRef())
    }

    override fun toScopeRef(): String = buildRef()

    private fun buildRef(): String = "browser-tab:$tabId:gen=$navigationGeneration"
}

/**
 * A time-bounded UI automation session (doc 9 sections 2 and 5.2): the allowed target packages,
 * the denied packages, the action budget and the session deadline. Sessions are created at
 * execution time and expire on their own; an Advanced profile may only adjust the budget inside
 * [MAX_AUTOMATION_ACTIONS_HARD_CAP] and never widen the target set silently.
 */
data class AutomationSessionScope(
    val allowedPackages: Set<String>,
    val deniedPackages: Set<String>,
    val maxActions: Int,
    val expiresAt: Instant,
) : UserScope {
    init {
        require(allowedPackages.isNotEmpty()) { "allowedPackages must not be empty" }
        allowedPackages.forEach { requireAndroidPackageName(it) }
        deniedPackages.forEach { requireAndroidPackageName(it) }
        val overlap = allowedPackages.intersect(deniedPackages)
        require(overlap.isEmpty()) { "a package cannot be both allowed and denied: $overlap" }
        require(maxActions in 1..UserScope.MAX_AUTOMATION_ACTIONS_HARD_CAP) {
            "maxActions must be 1..${UserScope.MAX_AUTOMATION_ACTIONS_HARD_CAP}: $maxActions"
        }
        requireRefLength(buildRef())
    }

    override fun toScopeRef(): String = buildRef()

    private fun buildRef(): String =
        "automation:allowed=" +
            allowedPackages.sorted().joinToString(",") +
            ":denied=" +
            deniedPackages.sorted().joinToString(",") +
            ":max=$maxActions:expires=${expiresAt.epochSecond}"
}

/**
 * A single user-initiated, short-lived Root session (doc 9 sections 2 and 6.2). By default only
 * high-level Root tools are allowed ([highLevelToolsOnly]); the type-level TTL bound is
 * [MAX_ROOT_SESSION_TTL_MINUTES] while the product default (10 minutes of inactivity) is set by
 * the session manager, not the type.
 */
data class RootSessionScope(
    val startedAt: Instant,
    val expiresAt: Instant,
    val highLevelToolsOnly: Boolean = true,
) : UserScope {
    init {
        require(!expiresAt.isBefore(startedAt)) { "expiresAt must not be before startedAt" }
        require(
            Duration.between(startedAt, expiresAt) <=
                Duration.ofMinutes(UserScope.MAX_ROOT_SESSION_TTL_MINUTES.toLong()),
        ) {
            "root session TTL must not exceed ${UserScope.MAX_ROOT_SESSION_TTL_MINUTES} minutes"
        }
        requireRefLength(buildRef())
    }

    override fun toScopeRef(): String = buildRef()

    private fun buildRef(): String = "root:expires=${expiresAt.epochSecond}:high-level=$highLevelToolsOnly"
}

private val IDENTITY_REGEX = Regex("[A-Za-z0-9_-]+")

private val ANDROID_PACKAGE_NAME_REGEX = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,50}")

private fun requireAndroidPackageName(packageName: String) {
    require(packageName.length in 1..UserScope.MAX_PACKAGE_NAME_LENGTH) {
        "package name must be 1..${UserScope.MAX_PACKAGE_NAME_LENGTH} chars: $packageName"
    }
    require(ANDROID_PACKAGE_NAME_REGEX.matches(packageName)) {
        "not a valid Android package name: $packageName"
    }
}

private fun requireCanonicalRoot(root: String) {
    require(root.length in 1..UserScope.MAX_ROOT_PATH_LENGTH) {
        "root must be 1..${UserScope.MAX_ROOT_PATH_LENGTH} chars: $root"
    }
    require(root.startsWith("/")) { "root must be an absolute path: $root" }
    require(!root.contains("//")) { "root must not contain empty path segments: $root" }
    require(root == "/" || !root.endsWith("/")) { "root must not end with a slash: $root" }
    root.split("/").forEach { segment ->
        require(segment != "." && segment != "..") { "root must be canonical (no dot segments): $root" }
    }
}

private fun requireRefLength(ref: String) {
    require(ref.length in 1..UserScope.MAX_SCOPE_REF_LENGTH) {
        "scope ref must be 1..${UserScope.MAX_SCOPE_REF_LENGTH} chars (got ${ref.length})"
    }
}
