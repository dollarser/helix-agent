package com.helix.app.terminal

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        setContent { HelixTheme { ManualTerminalScreen(model, directory, onBack = { finish() }) } }
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
