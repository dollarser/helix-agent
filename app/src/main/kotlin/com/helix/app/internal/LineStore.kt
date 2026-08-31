package com.helix.app.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Minimal line-oriented persistence for app-level UI state (safety profile, cleartext
 * bindings, provider test statuses, first-launch flag).
 *
 * Why not Room: this is app UI state, not product data — the Room schema
 * (doc 02 section 9) is the sole normative field list for sessions/messages/
 * providers/goals, and HXA-028 adds no persisted product fields. One string per
 * line keeps the encoding dependency-free (no JSON), trivially unit-testable on
 * the JVM, and free of secret material (NFR-007: nothing stored here may ever
 * carry a key or token — aliases only).
 */
interface LineStore {
    fun lines(key: String): List<String>

    fun setLines(
        key: String,
        lines: List<String>,
    )
}

/** SharedPreferences-backed [LineStore]; each key is one SharedPreferences entry (newline-joined). */
class PrefsLineStore(
    context: Context,
    name: String,
) : LineStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun lines(key: String): List<String> = prefs.getString(key, null)?.split(LINE_SEPARATOR).orEmpty()

    override fun setLines(
        key: String,
        lines: List<String>,
    ) {
        // core-ktx edit{} (apply() semantics): core-ktx is already on the locked
        // app classpath (transitive of activity-compose); no new dependency.
        prefs.edit {
            if (lines.isEmpty()) {
                remove(key)
            } else {
                putString(key, lines.joinToString(LINE_SEPARATOR))
            }
        }
    }

    private companion object {
        const val LINE_SEPARATOR = "\n"
    }
}

/** In-memory [LineStore] for JVM unit tests and previews. */
class InMemoryLineStore : LineStore {
    private val data = HashMap<String, List<String>>()
    private val lock = Any()

    override fun lines(key: String): List<String> = synchronized(lock) { data[key].orEmpty() }

    override fun setLines(
        key: String,
        lines: List<String>,
    ) {
        synchronized(lock) {
            if (lines.isEmpty()) data.remove(key) else data[key] = lines.toList()
        }
    }
}
