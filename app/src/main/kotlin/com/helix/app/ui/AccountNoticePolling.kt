package com.helix.app.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.helix.app.account.AccountSession
import kotlinx.coroutines.delay

/** Foreground-only, shared/coalesced count checks. No background service or push permission needed. */
@Composable
@Suppress("FunctionName")
internal fun AccountNoticePolling(account: AccountSession) {
    val owner = LocalLifecycleOwner.current
    val connectivity = LocalContext.current.getSystemService(ConnectivityManager::class.java)
    val identity by account.identityState.collectAsStateWithLifecycle()
    LaunchedEffect(account, owner, identity) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                    account.notices.refreshUnread()
                }
                delay(60_000)
            }
        }
    }
}
