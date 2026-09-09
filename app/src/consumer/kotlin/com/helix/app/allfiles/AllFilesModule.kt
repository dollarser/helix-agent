package com.helix.app.allfiles

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.app.profile.SafetyProfileStore
import java.nio.file.Path

/**
 * Consumer does not expose developer af-* Agent scopes. The separately authorized, user-operated
 * shared-storage browser is supplied by SharedStorageAccess under ADR-0036 in both flavors.
 */
internal object AllFilesModule {
    /** Matches the developer flavor's prefix so shared routing stays variant-neutral. */
    const val SCOPE_ID_PREFIX: String = "af-"

    const val AVAILABLE: Boolean = false

    /** No-op for developer Agent scopes; manual file browsing uses a separate resolver. */
    @Suppress("UnusedParameter")
    fun init(context: Context) {
        // No developer af-* registry is initialized.
    }

    /** Always refuses — a consumer `af-<root>` scope can never resolve to a real path. */
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
    fun resolveScopeRoot(scopeId: String): Path? = null

    /** No developer Agent roots; manual shared storage is added by AppFileServices. */
    fun allFilesSources(): List<AllFilesSource> = emptyList()

    /** Unreachable in the consumer build (`AVAILABLE == false` guards the navigation). */
    @Composable
    @SuppressLint("ComposableNaming")
    @Suppress("UnusedParameter")
    fun render(profileStore: SafetyProfileStore) {
        Unit
    }
}
