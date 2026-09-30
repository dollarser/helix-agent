package com.helix.app.terminal

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.helix.app.HelixApplication
import com.helix.app.HelixTheme
import com.helix.app.language.AppLanguageStore

/** User-operated terminal; neither exported nor registered as an Agent tool. */
class ManualTerminalActivity : ComponentActivity() {
    private lateinit var model: ManualTerminalViewModel

    override fun attachBaseContext(base: Context) {
        val locale = AppLanguageStore.effectiveLocaleList(base)
        super.attachBaseContext(AppLanguageStore.wrapForLocale(base, locale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val terminal = checkNotNull((application as HelixApplication).appContainer.manualTerminal)
        model = ViewModelProvider(this, Factory(terminal))[ManualTerminalViewModel::class.java]
        val directory = intent.getStringExtra(DIRECTORY).orEmpty().ifBlank { "." }
        setContent {
            HelixTheme {
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
                            (application as HelixApplication).appContainer.profileStore,
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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) model.disconnect()
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
