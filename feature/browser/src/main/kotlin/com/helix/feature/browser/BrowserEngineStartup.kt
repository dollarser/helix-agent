package com.helix.feature.browser

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BrowserEngineState { IDLE, STARTING, READY, FAILED }

/** Process-wide engine preparation. Never retains an Activity or creates a page/blank WebView. */
internal object BrowserEngineStartup {
    private val mutableState = MutableStateFlow(BrowserEngineState.IDLE)
    val state = mutableState.asStateFlow()

    fun start(
        context: Context,
        retry: Boolean = false,
    ) {
        if (state.value != BrowserEngineState.IDLE && !(retry && state.value == BrowserEngineState.FAILED)) return
        mutableState.value = BrowserEngineState.STARTING
        try {
            WebViewCompat.startUpWebView(
                context.applicationContext,
                WebViewStartUpConfig.Builder(Dispatchers.Default.asExecutor()).build(),
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        mutableState.value = BrowserEngineState.READY
                    }

                    override fun onError(error: WebViewStartupException) {
                        mutableState.value = BrowserEngineState.FAILED
                    }
                },
            )
        } catch (_: RuntimeException) {
            // A provider startup failure is an unavailable engine, never a successful prewarm.
            mutableState.value = BrowserEngineState.FAILED
        }
    }
}
