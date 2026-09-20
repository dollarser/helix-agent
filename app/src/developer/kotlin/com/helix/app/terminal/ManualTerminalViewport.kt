package com.helix.app.terminal

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/** Bounded UI transport; accepted input is never automatically replayed after a failed IPC. */
private class TerminalInput {
    val bytes = Channel<ByteArray>(4)
    val sizes = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    val failed = MutableStateFlow(false)

    fun offer(value: ByteArray) {
        if (value.isEmpty()) return
        if (value.size > MAX_CHUNK || !bytes.trySend(value.copyOf()).isSuccess) failed.value = true
    }

    companion object {
        const val MAX_CHUNK = 8192
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ManualTerminalViewport(
    connection: ManualTerminal.Connection,
    keyboard: Boolean,
    modifier: Modifier,
    onEnded: () -> Unit,
) {
    val input = remember(connection) { TerminalInput() }
    val inputFailed by input.failed.collectAsState()
    val feed = remember(connection) { TerminalFeed() }
    LaunchedEffect(connection) {
        transport(onFailure = {
            input.failed.value = true
            input.bytes.close()
        }) {
            for (bytes in input.bytes) {
                if (connection.write(bytes) != "ACCEPTED") input.failed.value = true
            }
        }
    }
    LaunchedEffect(connection) {
        transport(onFailure = {
            input.failed.value = true
            input.sizes.close()
        }) {
            for ((rows, columns) in input.sizes) connection.resize(rows, columns)
        }
    }
    DisposableEffect(input) {
        onDispose {
            input.bytes.close()
            input.sizes.close()
        }
    }
    Column(modifier) {
        if (inputFailed) {
            Text(
                stringResource(R.string.terminal_input_rejected),
                Modifier.testTag("terminal-input-error"),
            )
        }
        if (feed.failed) Text(stringResource(R.string.terminal_output_failed))
        if (feed.gap) Text(stringResource(R.string.terminal_output_gap), Modifier.testTag("terminal-output-gap"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Ctrl-C" to 3, "Tab" to 9, "Esc" to 27, "Ctrl-D" to 4).forEach { (label, value) ->
                TextButton(
                    onClick = { input.offer(byteArrayOf(value.toByte())) },
                    modifier = Modifier.testTag("terminal-key-$value"),
                ) {
                    Text(label)
                }
            }
        }
        key(connection, feed.epoch) {
            val emulator =
                remember {
                    TerminalEmulatorFactory.create(
                        onKeyboardInput = input::offer,
                        onResize = { input.sizes.trySend(it.rows.coerceIn(1, 512) to it.columns.coerceIn(1, 512)) },
                    )
                }
            DisposableEffect(emulator) {
                onDispose {
                    // Composition removal cancels producers and disposes Terminal's view first.
                    // Post to the same callback looper, after this disposal pass has completed.
                    Handler(Looper.getMainLooper()).post { emulator.close() }
                }
            }
            LaunchedEffect(emulator) {
                transport(onFailure = {
                    feed.failed = true
                    onEnded()
                }) {
                    feed.read(connection, emulator, onEnded)
                }
            }
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.fillMaxSize().testTag("terminal-viewport"),
                minFontSize = 10.sp,
                initialFontSize = 12.sp,
                keyboardEnabled = true,
                showSoftKeyboard = keyboard,
            )
        }
    }
}

private class TerminalFeed {
    var failed by mutableStateOf(false)
    var gap by mutableStateOf(false)
    var epoch by mutableIntStateOf(0)
    private var cursor: String? = null
    private var pending: ManualTerminal.Output? = null

    suspend fun read(
        connection: ManualTerminal.Connection,
        emulator: TerminalEmulator,
        onEnded: () -> Unit,
    ) {
        var fresh = true
        while (true) {
            val page = pending?.also { pending = null } ?: connection.read(cursor)
            if (page.gapBefore) {
                gap = true
                if (!fresh) {
                    // Missing ANSI/UTF-8 bytes require a fresh parser, not clearScreen.
                    pending = page
                    epoch++
                    return
                }
            }
            emulator.writeInput(page.bytes)
            cursor = page.cursor
            fresh = false
            if (page.eof) {
                onEnded()
                return
            }
            if (page.bytes.isEmpty()) delay(25)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // Error is visible; uncertain transport is never retried.
private suspend fun transport(onFailure: () -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure()
    }
}
