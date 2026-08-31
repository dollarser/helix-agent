package com.helix.app.capability

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.WebView
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.policy.GrantState

/**
 * The production [CapabilityResolver] (HXA-032, platform capabilities doc section 2): every
 * capability is checked against the real system state on every call — runtime permission state,
 * [Environment.isExternalStorageManager], the enabled accessibility-services list, WebView
 * presence. Nothing is cached; the `capability_grants` rows written through the recorder are
 * audit only (architecture doc 9.1: 权限状态缓存，不代替实时检查).
 *
 * Honest unavailable (doc 9 section 6.2: never use "detected su" as a grant): Root reports
 * [GrantState.UNAVAILABLE] until the libsu integration lands behind the HXA-094 gate, and
 * Accessibility reports [GrantState.UNAVAILABLE] while this build ships no accessibility service
 * component. [CapabilityGrant.userScope] is null at capability level: the per-call scope binding
 * (which tree, which tab, which session) is decided by the dispatcher at execution time.
 */
class SystemCapabilityResolver(
    private val context: Context,
    private val clock: Clock = SystemClock(),
) : CapabilityResolver {
    override fun resolve(capability: Capability): CapabilityGrant {
        val state = systemState(capability)
        return CapabilityGrant(
            capability = capability,
            state = state,
            grantedBySystem = true,
            userScope = null,
            checkedAt = clock.now(),
        )
    }

    private fun systemState(capability: Capability): GrantState =
        when (capability) {
            Capability.WEB_BROWSING -> {
                if (WebView.getCurrentWebViewPackage() != null) {
                    GrantState.GRANTED
                } else {
                    GrantState.UNAVAILABLE
                }
            }

            // SAF has been a platform feature since API 21 (minSdk 29); the tree scope itself is
            // user-granted per selection, not a capability-level state.
            Capability.SAF_DOCUMENT_TREE -> {
                GrantState.GRANTED
            }

            Capability.MANAGE_ALL_FILES -> {
                manageAllFilesState()
            }

            Capability.ACCESSIBILITY_AUTOMATION -> {
                accessibilityState()
            }

            // libsu is gated behind the HXA-094 dependency ADR; no root integration in this build.
            Capability.ROOT_SHELL -> {
                GrantState.UNAVAILABLE
            }

            Capability.NOTIFICATION_READ -> {
                notificationState()
            }

            Capability.CALENDAR_WRITE -> {
                permissionState(Manifest.permission.WRITE_CALENDAR)
            }
        }

    private fun manageAllFilesState(): GrantState {
        if (Build.VERSION.SDK_INT < 30) {
            // MANAGE_EXTERNAL_STORAGE is an API 30+ platform feature.
            return GrantState.UNAVAILABLE
        }
        return if (Environment.isExternalStorageManager()) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }
    }

    /**
     * A service component must be declared in this build before it can be enabled; otherwise the
     * capability is honestly unavailable (not "denied by the user"). Once the automation service
     * lands (tools:automation), the same probe reports granted/denied from the enabled list.
     */
    private fun accessibilityState(): GrantState {
        val serviceComponents = accessibilityServiceComponents()
        if (serviceComponents.isEmpty()) {
            return GrantState.UNAVAILABLE
        }
        val enabled =
            Settings.Secure
                .getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
        return if (enabled.split(':').any { entry -> serviceComponents.any { it.flattenToString() == entry } }) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }
    }

    private fun accessibilityServiceComponents(): List<ComponentName> {
        val info =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            }.getOrNull() ?: return emptyList()
        return info.services
            .orEmpty()
            .mapNotNull { service ->
                runCatching {
                    context.packageManager.getServiceInfo(
                        ComponentName(context.packageName, service.name),
                        0,
                    )
                }.getOrNull()
            }.filter { it.metaData?.containsKey(A11Y_SERVICE_META) == true }
            .map { ComponentName(context.packageName, it.name) }
    }

    private fun notificationState(): GrantState {
        if (Build.VERSION.SDK_INT < 33) {
            // Legacy notifications are always allowed below API 33.
            return GrantState.GRANTED
        }
        return permissionState(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun permissionState(permission: String): GrantState =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }

    private companion object {
        const val A11Y_SERVICE_META = "android.accessibilityservice"
    }
}
