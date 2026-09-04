package com.helix.tools.android

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import java.net.URI
import java.net.URISyntaxException

/**
 * The production [AndroidSystemBridge] (roadmap HXA-064, doc 09 §5.3/§5.4). Context-backed: it
 * builds the real Android `Intent`s, drives the real system `ClipboardManager`, and enforces the
 * security controls the port's KDoc promises:
 *
 * - [openUri] only OPENS an http/https URL in the OS handler (`android.open_uri` 只打开). The scheme
 *   gate ([isHttpUrl]) runs BEFORE any intent is built, so a non-http(s) URL never reaches the
 *   launcher; a launch failure is a stable [OpenUriStatus.NO_HANDLER] / [OpenUriStatus.ERROR], never
 *   a fake success.
 * - [clipboardRead] / [clipboardWrite] refuse unless [foregroundProbe] reports this app is the
 *   visible-foreground app (doc 09: clipboard read/write 按可见前台限制).
 * - [share] only builds + launches an `ACTION_SEND` chooser — Helix never picks a target app; the
 *   user always chooses. The share text is previewed by the L2 approval card before the user approves.
 *
 * The port never throws for a page/system condition; a genuine failure is a stable ERROR outcome.
 *
 * [foregroundProbe] and [launcher] are injectable seams so the instrumented test can assert the REAL
 * intent building + gating + a real ClipboardManager round-trip WITHOUT actually launching another
 * app or depending on flaky emulator foreground state (the production defaults below are what the
 * app container uses — it just constructs [AndroidSystemBridgeImpl] with the application Context).
 */
class AndroidSystemBridgeImpl(
    private val context: Context,
    private val foregroundProbe: ForegroundProbe = ActivityManagerForegroundProbe(context),
    private val launcher: IntentLauncher = ContextIntentLauncher(context),
) : AndroidSystemBridge {
    override fun openUri(url: String): OpenUriOutcome {
        // Scheme gate FIRST: a non-http(s) URL never reaches the launcher (只打开 http/https).
        if (!isHttpUrl(url)) {
            return OpenUriOutcome(OpenUriStatus.REFUSED, url, "scheme")
        }
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        return when (launcher.launch(intent)) {
            LaunchResult.LAUNCHED -> OpenUriOutcome(OpenUriStatus.OPENED, url, "")
            LaunchResult.NO_HANDLER -> OpenUriOutcome(OpenUriStatus.NO_HANDLER, url, "no app to open")
            LaunchResult.ERROR -> OpenUriOutcome(OpenUriStatus.ERROR, url, "launch failed")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // stable outcomes, never a crash
    override fun clipboardRead(): ClipboardReadOutcome {
        if (!foregroundProbe.isForeground()) {
            return ClipboardReadOutcome(ClipboardReadStatus.REFUSED, "", 0, false, "not-foreground")
        }
        return try {
            val cm =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return ClipboardReadOutcome(ClipboardReadStatus.ERROR, "", 0, false, "no clipboard service")
            val full = (cm.primaryClip?.getItemAt(0)?.coerceToText(context) ?: "").toString()
            val bounded = boundClipboardText(full)
            ClipboardReadOutcome(ClipboardReadStatus.READ, bounded.text, bounded.length, bounded.truncated, "")
        } catch (e: Exception) {
            ClipboardReadOutcome(ClipboardReadStatus.ERROR, "", 0, false, "clipboard read failed")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // stable outcomes, never a crash
    override fun clipboardWrite(text: String): ClipboardWriteOutcome {
        if (!foregroundProbe.isForeground()) {
            return ClipboardWriteOutcome(ClipboardWriteStatus.REFUSED, 0, "not-foreground")
        }
        return try {
            val cm =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return ClipboardWriteOutcome(ClipboardWriteStatus.ERROR, 0, "no clipboard service")
            cm.setPrimaryClip(ClipData.newPlainText("Helix", text))
            ClipboardWriteOutcome(ClipboardWriteStatus.WRITTEN, text.length, "")
        } catch (e: Exception) {
            ClipboardWriteOutcome(ClipboardWriteStatus.ERROR, 0, "clipboard write failed")
        }
    }

    override fun share(
        text: String,
        subject: String,
    ): ShareOutcome {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (subject.isNotBlank()) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
            }
        val chooser = Intent.createChooser(send, null)
        return when (launcher.launch(chooser)) {
            LaunchResult.LAUNCHED -> ShareOutcome(ShareStatus.SHARED, "")
            LaunchResult.NO_HANDLER -> ShareOutcome(ShareStatus.NO_HANDLER, "no app to share to")
            LaunchResult.ERROR -> ShareOutcome(ShareStatus.ERROR, "share failed")
        }
    }
}

/**
 * Pure-JVM http/https gate (doc 09 §5.3): true only for an absolute URL whose scheme is exactly
 * `http` or `https` and that carries a non-blank host. Anything else — `file:`, `javascript:`,
 * `market:`, `tel:`, an opaque `http:foo`, blank, or an unparseable string — is false, so a
 * non-http(s) URL never reaches the launcher. `internal` so the unit test pins the boundary.
 */
@Suppress("SwallowedException", "ReturnCount") // unparseable URL = not http; early returns
internal fun isHttpUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val uri =
        try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return false
        }
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}

/**
 * The bounded view of clipboard text returned to the model: [text] capped at [maxChars], [length]
 * the ORIGINAL char count (so the model sees how long it was), [truncated] when it was cut. Pure
 * JVM (no Android types) so the bound is unit-testable in isolation; the port's read path and this
 * share [MAX_CLIPBOARD_READ], so the emitted text is always within the tool's output schema.
 */
internal data class BoundedClipboard(
    val text: String,
    val length: Int,
    val truncated: Boolean,
)

/**
 * Bounds [full] to [maxChars] chars. A string longer than the bound is cut to the first [maxChars]
 * chars and flagged [BoundedClipboard.truncated]; a shorter (or equal-length) string passes through
 * unchanged with `truncated=false`. [BoundedClipboard.length] is ALWAYS the original length, not the
 * bounded one — that is the field that tells the model the clip was longer than what it received.
 */
internal fun boundClipboardText(
    full: String,
    maxChars: Int = MAX_CLIPBOARD_READ,
): BoundedClipboard =
    if (full.length > maxChars) {
        BoundedClipboard(full.take(maxChars), full.length, true)
    } else {
        BoundedClipboard(full, full.length, false)
    }

/** Whether this app is the visible-foreground app (the clipboard read/write gate, doc 09). */
interface ForegroundProbe {
    fun isForeground(): Boolean
}

/**
 * The production [ForegroundProbe]: the app is foreground iff its own process reports
 * [ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND]. [ActivityManager.getRunningAppProcesses]
 * is deprecated but still the only self-contained (no-Activity, no-receiver) way to read our own
 * process importance; it always at least reports the calling app.
 */
class ActivityManagerForegroundProbe(
    private val context: Context,
) : ForegroundProbe {
    @Suppress("DEPRECATION", "ReturnCount") // getRunningAppProcesses is the only self-contained foreground probe
    override fun isForeground(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val pid = Process.myPid()
        val processes = am.getRunningAppProcesses() ?: return false
        return processes.any {
            it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}

/** The result of launching an [Intent] (a closed set; never a throw). */
enum class LaunchResult { LAUNCHED, NO_HANDLER, ERROR }

/** Launches an [Intent] on the app's behalf (a seam so the device test records intents without firing). */
interface IntentLauncher {
    fun launch(intent: Intent): LaunchResult
}

/**
 * The production [IntentLauncher]. A non-Activity (application) Context MUST launch into a new task,
 * so [Intent.FLAG_ACTIVITY_NEW_TASK] is added here — a transport concern kept out of the security
 * content the impl builds (action / data / extras). A missing handler is a stable [LaunchResult.NO_HANDLER];
 * any other failure is [LaunchResult.ERROR] (never a crash).
 */
class ContextIntentLauncher(
    private val context: Context,
) : IntentLauncher {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any launch failure is a stable ERROR, never a crash
    override fun launch(intent: Intent): LaunchResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.LAUNCHED
        } catch (e: android.content.ActivityNotFoundException) {
            LaunchResult.NO_HANDLER
        } catch (e: Exception) {
            LaunchResult.ERROR
        }
    }
}
