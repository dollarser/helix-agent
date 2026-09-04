package com.helix.feature.files.allfiles

/**
 * ONE user-selectable all-files root (platform capabilities doc section 4.1 step 4: the user
 * picks one or more roots the agent may use — never "all phone files").
 *
 * [key] is a stable, opaque slug; the all-files scope id is `af-<key>` (a legal
 * [com.helix.core.workspace.FileScopePath] scope id — the model only ever sees that). [labelRes] is the
 * string-resource id of the user-visible root name (HXA-069: this pure JVM catalog emits a stable
 * res id, never locale text — the Android UI resolves it via `stringResource`). [directoryType] is the
 * `android.os.Environment` public-directory type the root resolves to (a plain string here
 * so this catalog stays pure JVM; the app layer resolves it to a real path via
 * `Environment.getExternalStoragePublicDirectory`).
 *
 * The catalog is deliberately a FIXED, bounded, closed set of well-known public-storage
 * directories. There is no arbitrary-path entry in this milestone (an in-app directory browser is
 * HXA-046); bounding the roots this way is what makes "even with `MANAGE_EXTERNAL_STORAGE`,
 * out-of-scope paths are still refused" a property the user can reason about.
 */
data class AllFilesRoot(
    val key: String,
    val labelRes: Int,
    val directoryType: String,
)

/**
 * The fixed catalog of all-files roots (HXA-045). Bounded and closed: the agent can address ONLY
 * these roots (each as a distinct scope), so granting `MANAGE_EXTERNAL_STORAGE` never turns the
 * whole filesystem into an agent scope.
 */
object AllFilesRootCatalog {
    /** The all-files scope-id prefix (see [scopeId]). */
    const val SCOPE_ID_PREFIX: String = "af-"

    /** The bounded, closed set of roots the user may enable. */
    val ROOTS: List<AllFilesRoot> =
        listOf(
            AllFilesRoot("download", R.string.allfiles_root_download, "Download"),
            AllFilesRoot("documents", R.string.allfiles_root_documents, "Documents"),
            AllFilesRoot("pictures", R.string.allfiles_root_pictures, "Pictures"),
            AllFilesRoot("music", R.string.allfiles_root_music, "Music"),
            AllFilesRoot("movies", R.string.allfiles_root_movies, "Movies"),
            AllFilesRoot("podcasts", R.string.allfiles_root_podcasts, "Podcasts"),
            AllFilesRoot("dcim", R.string.allfiles_root_dcim, "DCIM"),
        )

    /** The catalog root for [key], or null when [key] is not in the catalog (fail closed). */
    fun byKey(key: String): AllFilesRoot? = ROOTS.firstOrNull { it.key == key }

    /**
     * The model-visible scope id of a root: `af-<key>`. Deterministic, opaque, and always a legal
     * [com.helix.core.workspace.FileScopePath] scope id.
     */
    fun scopeId(key: String): String = SCOPE_ID_PREFIX + key
}
