package com.helix.runtime.proot.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.ProotRuntimeServiceBinder

/**
 * The companion's bound-only runtime service (HXA-083; ADR-0007).
 *
 * Lifecycle contract:
 * - Cold-started by the main app's explicit `ComponentName` + `BIND_AUTO_CREATE`
 *   bind; never self-started, never kept resident, no foreground service, no
 *   wake lock (083 scope; jobs are HXA-084).
 * - [onBind] returns the binder UNCONDITIONALLY. The platform's signature-level
 *   bind permission already ran before the service was delivered, and the caller
 *   re-verification (section 6.6) lives in the binder's onTransact — the ONLY
 *   place that carries the live cross-APK caller identity (onBind is a
 *   main-thread lifecycle callback; `Binder.getCallingUid()` there returns this
 *   process's own uid, so it cannot verify the client there). A rejecting
 *   verifier answers REPLY_CALLER_MISMATCH on the transaction; the client maps
 *   it to a stable unavailable state and there is no fallback executor.
 * - [onUnbind] returns false: no rebinding. Once the last client unbinds, the
 *   process is reclaimable by the system; the next use cold-starts again.
 */
class ProotRuntimeService : Service() {
    override fun onBind(intent: Intent): IBinder? {
        // DEBUG-only null-bind seam (same house pattern as the TX_DEBUG_SELF_KILL
        // seam): lets the device test drive the client's onNullBinding path
        // (immediate BIND_REFUSED, never a fake timeout). Release ignores it.
        if (BuildConfig.DEBUG && intent.getBooleanExtra(ProotRuntimeProtocol.EXTRA_DEBUG_NULL_BIND, false)) {
            return null
        }
        // The manifest is derived fresh per bind (see ProotHandshakeManifest): a
        // rebinding process inherits no authority from a previous incarnation.
        // The verifier runs per-transaction inside the binder (see its KDoc);
        // debug builds additionally enable the crash-injection seam so the device
        // test can drive the binder-death path.
        return ProotRuntimeServiceBinder(
            manifestProvider = { ProotHandshakeManifest.build(this) },
            callerVerifier = { uid -> ProotCallerVerifier.verify(this, uid) },
            debugSelfKill = BuildConfig.DEBUG,
            jobHandler = ProotJobRunner.get(this),
        )
    }

    override fun onUnbind(intent: Intent): Boolean = false
}
