package com.helix.extensions.mobileuse.automation

/** Public runtime-permission choices, not arbitrary sensitive fields in a trusted package. */
internal fun publicPermissionControl(
    trustedController: String?,
    packageName: String?,
    viewId: String?,
    password: Boolean,
    editable: Boolean,
): Boolean =
    trustedController != null && packageName == trustedController && !password && !editable &&
        viewId?.substringAfter(":id/", "") in
        setOf(
            "permission_message",
            "permission_allow_button",
            "permission_deny_button",
            "permission_deny_and_dont_ask_again_button",
            "permission_allow_foreground_only_button",
            "permission_allow_one_time_button",
            "permission_allow_always_button",
        ) && viewId?.substringBefore(":id/") in setOf(trustedController, "com.android.permissioncontroller")

/** Public installer confirmation only; trusted identity is resolved by the system, not the model. */
internal fun publicInstallerControl(
    trustedInstaller: String?,
    packageName: String?,
    viewId: String?,
    password: Boolean,
    editable: Boolean,
    className: String?,
): Boolean =
    trustedInstaller != null && trustedInstaller == packageName && !password && !editable &&
        className == "android.widget.Button" && viewId in
        setOf(
            "android:id/button1",
            "android:id/button2",
            "$trustedInstaller:id/button1",
            "$trustedInstaller:id/button2",
            "com.android.packageinstaller:id/button1",
            "com.android.packageinstaller:id/button2",
        )
