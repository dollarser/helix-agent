package com.helix.app.automation.shizuku

import android.content.Context
import com.helix.tools.root.LibsuRootAccess

/** Mobile Use must survive switching to its target app; independent of foreground-only root.*. */
internal object MobileUseRootConnection {
    private var connection: LibsuRootAccess? = null

    @Synchronized
    fun configure(context: Context) {
        if (connection ==
            null
        ) {
            connection = LibsuRootAccess(context.applicationContext, RootAutomationService::class.java)
        }
    }

    @Synchronized
    fun access(): LibsuRootAccess? = connection

    /** Explicit user action only. Capability queries and tool dispatch never call this. */
    fun requestFromUser() = access()?.requestRoot()

    fun disconnect() = access()?.disconnect()
}
