package com.helix.app.allfiles

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.app.profile.SafetyProfileStore
import java.nio.file.Path

/**
 * The all-files capability module (HXA-045, ADR-0005): the CONSUMER flavor's side of the
 * per-variant [AllFilesModule] seam (same FQN as the developer implementation).
 *
 * The consumer channel ships NO all-files capability (ADR-0005/0006: no Standard → Advanced path).
 * [AVAILABLE] is false, [resolveScopeRoot] always refuses, and the shared permissions destination
 * keeps its generic empty state. `:feature:files-allfiles` is a `developerImplementation`, so this
 * object deliberately references none of its types.
 */
internal object AllFilesModule {
    /** Matches the developer flavor's prefix so shared routing stays variant-neutral. */
    const val SCOPE_ID_PREFIX: String = "af-"

    const val AVAILABLE: Boolean = false

    /** No-op seam: the consumer build ships no all-files capability (ADR-0005/0006). */
    @Suppress("UnusedParameter")
    fun init(context: Context) {
        // No-op: the consumer build ships no all-files capability.
    }

    /** Always refuses — a consumer `af-<root>` scope can never resolve to a real path. */
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
    fun resolveScopeRoot(scopeId: String): Path? = null

    /** No all-files roots to browse (HXA-046): the consumer build ships no all-files capability. */
    fun allFilesSources(): List<AllFilesSource> = emptyList()

    /** Unreachable in the consumer build (`AVAILABLE == false` guards the navigation). */
    @Composable
    @SuppressLint("ComposableNaming")
    @Suppress("UnusedParameter")
    fun render(profileStore: SafetyProfileStore) {
        Unit
    }
}
