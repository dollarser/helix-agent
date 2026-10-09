package com.helix.app.automation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Draft UI state never grants a scope. Only a successful store write changes authority. */
internal class MobileUseSettingsState(
    private val store: MobileUseGrantStore,
) {
    var packages by mutableStateOf(emptyList<String>())
    var wholePhone by mutableStateOf(true)
    var loaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
    private var configured by mutableStateOf(false)
    private var baselinePackages by mutableStateOf(emptyList<String>())
    private var baselineWholePhone by mutableStateOf(true)

    val dirty get() = wholePhone != baselineWholePhone || (!wholePhone && packages.toSet() != baselinePackages.toSet())
    val saved get() = configured && !dirty && !failed
    val canSave get() = loaded && !busy && (wholePhone || packages.isNotEmpty())

    fun discard() {
        packages = baselinePackages
        wholePhone = baselineWholePhone
        failed = false
    }

    suspend fun load() {
        if (loaded || busy) return
        busy = true
        failed = false
        try {
            val config = withContext(Dispatchers.IO) { store.globalConfiguration() }
            packages =
                config
                    ?.scope
                    ?.allowedPackages
                    .orEmpty()
                    .toList()
            wholePhone = config?.scope?.allApplications != false
            configured = config != null
            baselinePackages = packages
            baselineWholePhone = wholePhone
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
        } finally {
            busy = false
        }
    }

    suspend fun save(): Boolean {
        if (!canSave) return false
        busy = true
        failed = false
        val selected = packages.toSet()
        val all = wholePhone
        return try {
            withContext(Dispatchers.IO) { store.configureGlobal(selected, all) }
            baselinePackages = selected.toList()
            baselineWholePhone = all
            configured = true
            true
        } catch (cancel: CancellationException) {
            // A database write may have committed. Re-read its authoritative result on return.
            loaded = false
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
            false
        } finally {
            busy = false
        }
    }

    companion object {
        fun saver(store: MobileUseGrantStore) =
            listSaver<MobileUseSettingsState, Any>(
                save = {
                    listOf(
                        it.packages.joinToString("\n"),
                        it.wholePhone,
                        it.loaded && !it.busy,
                        it.configured,
                        it.baselinePackages.joinToString("\n"),
                        it.baselineWholePhone,
                    )
                },
                restore = { values ->
                    MobileUseSettingsState(store).apply {
                        packages = (values[0] as String).lines().filter(String::isNotEmpty)
                        wholePhone = values[1] as Boolean
                        loaded = values[2] as Boolean
                        configured = values[3] as Boolean
                        baselinePackages = (values[4] as String).lines().filter(String::isNotEmpty)
                        baselineWholePhone = values[5] as Boolean
                    }
                },
            )
    }
}
