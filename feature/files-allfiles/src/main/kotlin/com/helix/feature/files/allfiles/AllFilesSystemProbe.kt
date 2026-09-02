package com.helix.feature.files.allfiles

/**
 * Answers "is `MANAGE_EXTERNAL_STORAGE` granted right now" — the live
 * `Environment.isExternalStorageManager()` result (platform capabilities doc section 4.1 step 3).
 *
 * The production implementation lives in the app layer (it calls the static Android API); tests
 * inject fakes so the all-files logic stays hermetic on the JVM. The result is a pure boolean and
 * is re-read on every check — never cached (a cached value would let the UI keep offering roots
 * after the user revokes the permission in system settings).
 */
fun interface AllFilesSystemProbe {
    fun isExternalStorageManager(): Boolean
}
