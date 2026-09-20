package com.helix.spike.termlib

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.ByteArrayOutputStream

class TerminalViewProbeActivity : ComponentActivity() {
    val received = ByteArrayOutputStream()
    val terminal = TerminalEmulatorFactory.create(onKeyboardInput = { synchronized(received) { received.write(it) } })
    var attached by mutableStateOf(true)
    var keyboard by mutableStateOf(false)
    @Volatile var imeVisible = false
    @Volatile var terminalBounds = Rect.Zero

    override fun onCreate(state: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                BasicText("Helix terminal / 终端组件验证")
                if (attached) Terminal(
                    terminalEmulator = terminal,
                    modifier = Modifier.weight(1f).onGloballyPositioned { terminalBounds = it.boundsInWindow() },
                    keyboardEnabled = true,
                    showSoftKeyboard = keyboard,
                    onImeVisibilityChanged = { imeVisible = it },
                ) else BasicText("Detached / 已断开视图")
            }
        }
        terminal.writeInput("\u001b[?25l\u001b[32mHelix PTY 终端\u001b[0m\r\nUTF-8 中文输入测试\r\nprobe> ".toByteArray())
    }

    fun receivedText(): String = synchronized(received) { received.toString("UTF-8") }

    override fun onDestroy() {
        // ComponentActivity destroys the composition before disposing the emulator.
        super.onDestroy()
        terminal.close()
    }
}
