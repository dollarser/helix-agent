package com.helix.app.plugin

import com.helix.app.R
import com.helix.extensions.skills.InvalidSkillException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.zip.ZipException

/** Fixed messages only: parser exceptions may include user documents, paths or credential values. */
internal enum class PluginImportFailure(
    val messageRes: Int,
) {
    INVALID(R.string.plugin_import_invalid),
    MARKETPLACE(R.string.plugin_import_marketplace),
    CONFLICT(R.string.plugin_import_conflict),
    LIMIT(R.string.plugin_import_limit),
    UNSUPPORTED(R.string.plugin_import_unsupported),
    UNSAFE_ARCHIVE(R.string.plugin_import_unsafe_archive),
    ACCESS(R.string.plugin_import_access),
    STORAGE(R.string.plugin_import_storage),
    UNKNOWN(R.string.plugin_import_failed),
    ;

    companion object {
        fun from(failure: Exception): PluginImportFailure {
            if (failure is CancellationException) throw failure
            val code = failure.message.orEmpty().substringBefore(':')
            codedFailure(code)?.let { return it }
            return when {
                failure is SecurityException || failure is java.io.FileNotFoundException -> ACCESS

                failure is ZipException || failure is InvalidSkillException -> INVALID

                failure is IOException -> STORAGE

                failure is IllegalArgumentException || code.startsWith("CONNECTOR_INVALID_") ||
                    code == "CONNECTOR_AMBIGUOUS_MANIFEST" -> INVALID

                else -> UNKNOWN
            }
        }

        private fun codedFailure(code: String): PluginImportFailure? =
            when (code) {
                "IMPORT_SOURCE_UNAVAILABLE" -> ACCESS

                "IMPORT_SOURCE_CHANGED" -> CONFLICT

                "PLUGIN_MARKETPLACE_NOT_PLUGIN" -> MARKETPLACE

                "CONNECTOR_VERSION_CHANGED",
                "CONNECTOR_CONTENT_CHANGED",
                "MARKETPLACE_CONTENT_CHANGED",
                "MARKETPLACE_SOURCE_CHANGED",
                -> CONFLICT

                "CONNECTOR_TOO_LARGE",
                "CONNECTOR_TOO_MANY_FILES",
                "CONNECTOR_TOO_MANY_SERVERS",
                "CONNECTOR_TOO_MANY_SKILLS",
                "CONNECTOR_CONFIG_TOO_LARGE",
                "CONNECTOR_COMPRESSION_RATIO",
                "MARKETPLACE_STORAGE_LIMIT",
                -> LIMIT

                "CONNECTOR_PATH_TRAVERSAL",
                "CONNECTOR_SPECIAL_FILE",
                "CONNECTOR_DUPLICATE_PATH",
                -> UNSAFE_ARCHIVE

                "CONNECTOR_NO_PORTABLE_COMPONENT",
                "CONNECTOR_EMPTY",
                "CONNECTOR_UNSUPPORTED_COMPRESSION",
                -> UNSUPPORTED

                else -> null
            }
    }
}
