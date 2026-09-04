package com.helix.app.allfiles

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.profile.SafetyProfileStore
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.GrantState
import com.helix.feature.files.allfiles.AllFilesRootCatalog
import com.helix.feature.files.allfiles.AllFilesRootsStore
import com.helix.feature.files.allfiles.AllFilesSystemProbe
import java.io.File
import java.nio.file.Path

/**
 * The all-files capability module (HXA-045, ADR-0005): the DEVELOPER flavor's side of the
 * per-variant [AllFilesModule] seam (same FQN as the consumer no-op, exactly like
 * [com.helix.app.profile.AdvancedProfileAvailability]).
 *
 * Holds the live system probe, the persisted Helix-roots registry, and the consent screen.
 * `:feature:files-allfiles` is a `developerImplementation`, so every reference to its types lives
 * in this flavor only — the shared `src/main` code (scope resolution + navigation) sees only this
 * object's variant-neutral surface ([AVAILABLE], [init], [resolveScopeRoot], [render]).
 *
 * Honesty contract (platform capabilities doc section 4.1): the UI never claims "the whole
 * phone" — the agent can address only the roots the user explicitly enables here, each as a
 * distinct scope, and every tool call still goes through scope/Policy/approval.
 */
internal object AllFilesModule {
    /**
     * The all-files scope-id prefix (mirrored by the consumer no-op so shared routing stays
     * variant-neutral). A model sees `scope:af-<root>:<relative>` — never a real path (doc 10).
     */
    const val SCOPE_ID_PREFIX: String = "af-"

    const val AVAILABLE: Boolean = true

    /**
     * The live `Environment.isExternalStorageManager()` probe (doc 02 section 9.1: re-read on
     * every call, never cached). Guarded to API 30+ — the method is absent on API 29.
     */
    private val probe =
        AllFilesSystemProbe {
            if (Build.VERSION.SDK_INT < 30) {
                false
            } else {
                Environment.isExternalStorageManager()
            }
        }

    private var rootsStore: AllFilesRootsStore? = null

    // HXA-069: the catalog carries stable string-resource ids for the root names; this object is
    // the seam that resolves them to the current locale (Application context — no lifecycle leak).
    private var appContext: Context? = null

    /** Builds the roots registry against the app-private `workspaces/` dir (idempotent). */
    fun init(context: Context) {
        appContext = context.applicationContext
        rootsStore ?: run {
            rootsStore =
                AllFilesRootsStore(File(context.filesDir, "workspaces/all-files-roots.json").toPath())
        }
    }

    fun isEnabled(key: String): Boolean = rootsStore?.isEnabled(key) ?: false

    // getExternalStoragePublicDirectory is the canonical public-dir resolver for MANAGE_EXTERNAL_STORAGE.
    @Suppress("DEPRECATION")
    fun enableRoot(key: String) {
        val store = rootsStore ?: return
        val directoryType = AllFilesRootCatalog.byKey(key)?.directoryType ?: return
        store.enable(key, Environment.getExternalStoragePublicDirectory(directoryType).toPath().toString())
    }

    fun disableRoot(key: String) {
        rootsStore?.disable(key)
    }

    /**
     * Resolves a model-visible scope id to its real root, or null (fail closed). A non-`af-` id,
     * a disabled root, or a lost `MANAGE_EXTERNAL_STORAGE` grant all refuse — so an out-of-scope or
     * un-granted reference never resolves to a real path (doc 10). The returned path is consumed
     * only by the containment check downstream; it is never handed to the model.
     */
    fun resolveScopeRoot(scopeId: String): Path? =
        when {
            !scopeId.startsWith(SCOPE_ID_PREFIX) -> null
            !probe.isExternalStorageManager() -> null
            else -> rootsStore?.resolveScopeRoot(scopeId)
        }

    /**
     * The enabled all-files roots the file manager can browse (HXA-046). A root appears only while
     * it is BOTH enabled AND the live `MANAGE_EXTERNAL_STORAGE` grant is present — a source whose
     * grant was revoked or disabled never surfaces, so the file manager never offers a scope it
     * cannot actually resolve.
     */
    fun allFilesSources(): List<AllFilesSource> =
        if (!probe.isExternalStorageManager()) {
            emptyList()
        } else {
            AllFilesRootCatalog.ROOTS
                .filter { isEnabled(it.key) }
                .map { root ->
                    // HXA-069: the catalog holds a stable res id; resolve it here. A root only
                    // appears while enabled, which requires init() — so appContext is set.
                    AllFilesSource(
                        AllFilesRootCatalog.scopeId(root.key),
                        appContext?.getString(root.labelRes) ?: root.key,
                    )
                }
        }

    /**
     * The live `MANAGE_EXTERNAL_STORAGE` state, read from the SAME probe the scope resolver uses —
     * a plain system query, safe on the UI thread. This deliberately does NOT go through the
     * capability center: `CapabilityCenter.check` writes an execution-time `capability_grants`
     * audit row via a blocking Room insert (doc 9 section 1), which must never run on the main
     * thread and must not fire merely because the user opened this screen.
     */
    private fun allFilesState(): GrantState =
        when {
            Build.VERSION.SDK_INT < 30 -> GrantState.UNAVAILABLE
            probe.isExternalStorageManager() -> GrantState.GRANTED
            else -> GrantState.DENIED
        }

    /**
     * The consent screen (platform capabilities doc section 4.1): an honest explanation, the live
     * system state (re-read on every resume from settings), the settings jump, and the bounded
     * root catalog. Root toggles are gated on BOTH the ADVANCED profile (ADR-0005) and a live
     * grant — Standard never exposes all-files scopes.
     *
     * `@SuppressLint` rationale: `ComposableNaming` — a member composable would have to be
     * PascalCase for the Compose check, but ktlint and detekt both require a lowercase member
     * function and shared routing calls `AllFilesModule.render`, so lowercase wins. `UseKtx` —
     * the settings jump builds its `package:` Uri via `Uri.parse` (this codebase's convention;
     * `:feature:files` does the same) and `:app` deliberately has no `core-ktx` dependency.
     */
    @Composable
    @SuppressLint("ComposableNaming", "UseKtx")
    @Suppress("FunctionName", "LongMethod")
    fun render(profileStore: SafetyProfileStore) {
        val context = LocalContext.current
        val profile by profileStore.flow.collectAsStateWithLifecycle()
        // Bumped on every resume (returning from system settings) and every toggle, so the live
        // system state and the enabled set are re-read; the initial read happens at first
        // composition.
        var version by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
        val state = remember(version) { allFilesState() }
        val enabledKeys =
            remember(version) {
                AllFilesRootCatalog.ROOTS.filter { isEnabled(it.key) }.mapTo(mutableSetOf()) { it.key }
            }
        val canToggle = state == GrantState.GRANTED && profile == SafetyProfile.ADVANCED

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("screen-permissions-allfiles"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.allfiles_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.allfiles_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("allfiles-explanation"),
            )
            Text(
                when (state) {
                    GrantState.GRANTED -> stringResource(R.string.allfiles_state_granted)
                    GrantState.DENIED -> stringResource(R.string.allfiles_state_denied)
                    GrantState.UNAVAILABLE -> stringResource(R.string.allfiles_state_unavailable)
                    GrantState.LOST -> stringResource(R.string.allfiles_state_lost)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("allfiles-grant-state"),
            )
            if (state == GrantState.DENIED) {
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.testTag("allfiles-open-settings"),
                ) {
                    Text(stringResource(R.string.allfiles_open_settings))
                }
            }
            if (profile != SafetyProfile.ADVANCED) {
                Text(
                    stringResource(R.string.allfiles_advanced_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("allfiles-advanced-required"),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.allfiles_roots_title), style = MaterialTheme.typography.titleMedium)
                AllFilesRootCatalog.ROOTS.forEach { root ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("allfiles-root-${root.key}"),
                    ) {
                        Checkbox(
                            checked = root.key in enabledKeys,
                            enabled = canToggle,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    enableRoot(root.key)
                                } else {
                                    disableRoot(root.key)
                                }
                                version++
                            },
                            modifier = Modifier.testTag("allfiles-root-toggle-${root.key}"),
                        )
                        Text(stringResource(root.labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
