package com.helix.extensions.mobileuse.automation.backend

/** A known refusal before creating a UI connection, display or input operation. */
internal class PrivilegedUiServiceUnavailable : IllegalStateException("PRIVILEGED_SERVICE_UNAVAILABLE")

internal fun requirePrivilegedUiServices(available: (String) -> Boolean) {
    if (!available("window") || !available("accessibility")) throw PrivilegedUiServiceUnavailable()
}
