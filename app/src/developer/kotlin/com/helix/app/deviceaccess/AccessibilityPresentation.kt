package com.helix.app.deviceaccess

import com.helix.app.R

internal data class AccessibilityPresentation(
    val status: Int,
    val action: Int,
    val help: Int,
)

internal fun accessibilityPresentation(
    granted: Int,
    connected: Int,
    total: Int,
): AccessibilityPresentation =
    when {
        total == 0 -> {
            AccessibilityPresentation(
                R.string.device_access_accessibility_none,
                R.string.permissions_system_settings,
                R.string.device_access_accessibility_help,
            )
        }

        granted < total -> {
            AccessibilityPresentation(
                R.string.permissions_not_granted,
                R.string.permissions_request,
                R.string.device_access_accessibility_help,
            )
        }

        connected < total -> {
            AccessibilityPresentation(
                R.string.device_access_accessibility_disconnected,
                R.string.device_access_accessibility_restore,
                R.string.device_access_accessibility_restore_help,
            )
        }

        else -> {
            AccessibilityPresentation(
                R.string.device_access_backend_ready,
                R.string.permissions_system_settings,
                R.string.device_access_accessibility_help,
            )
        }
    }
