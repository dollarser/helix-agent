package com.helix.app.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.HelixTheme
import com.helix.app.MainActivity
import com.helix.app.language.AppLanguageStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** User-operated terminal; neither exported nor registered as an Agent tool. */
class ManualTerminalActivity : ComponentActivity() {
    private lateinit var model: ManualTerminalViewModel
    private lateinit var profileStore: SafetyProfileStore

    override fun attachBaseContext(base: Context) {
        val locale = AppLanguageStore.effectiveLocaleList(base)
        super.attachBaseContext(AppLanguageStore.wrapForLocale(base, locale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { initialize() }
    }

    @Suppress("TooGenericExceptionCaught") // Startup failure must return to the foreground warning, never reset here.
    private suspend fun initialize() {
        val container =
            try {
                (application as HelixApplication).awaitAppContainer()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
        showTerminal(container)
    }

    private fun showTerminal(container: AppContainer) {
        val terminal = checkNotNull(container.manualTerminal)
        profileStore = container.profileStore
        model = ViewModelProvider(this, Factory(terminal))[ManualTerminalViewModel::class.java]
        val directory = intent.getStringExtra(DIRECTORY).orEmpty().ifBlank { "." }
        setContent {
            HelixTheme {
                ManualTerminalAccess(
                    profileStore,
                    onAvailable = {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.enter(directory)
                    },
                    onUnavailable = { model.disconnect() },
                    onBack = { finish() },
                ) {
                    val settings = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.activity.compose.BackHandler(settings.value) { settings.value = false }
                    if (settings.value) {
                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier
                                .systemBarsPadding(),
                        ) {
                            androidx.compose.material3.TextButton(onClick = { settings.value = false }) {
                                androidx.compose.material3.Text(getString(com.helix.app.R.string.files_back))
                            }
                            com.helix.app.ui.RuntimeSetupScreen(
                                container.profileStore,
                            )
                        }
                    } else {
                        ManualTerminalScreen(model, directory, onBack = { finish() }, onRuntimeSettings = {
                            settings.value =
                                true
                        })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized && profileStore.profile == SafetyProfile.ADVANCED) {
            model.enter(intent.getStringExtra(DIRECTORY).orEmpty().ifBlank { "." })
        }
    }

    override fun onStop() {
        super.onStop()
        if (::model.isInitialized && !isChangingConfigurations) model.disconnect()
    }

    private class Factory(
        private val terminal: ManualTerminal,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            modelClass.cast(ManualTerminalViewModel(terminal))!!
    }

    companion object {
        const val DIRECTORY = "workspaceDirectory"
    }
}
