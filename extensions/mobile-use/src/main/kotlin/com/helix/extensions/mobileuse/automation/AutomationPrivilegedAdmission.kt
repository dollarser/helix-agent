package com.helix.extensions.mobileuse.automation

import com.helix.extensions.mobileuse.config.MobileUseGrant
import com.helix.tools.framework.ExecutableToolCall
import java.time.Instant

/** Scope is the original call's durable user grant, independent of the Accessibility runtime. */
internal fun privilegedCallAdmitted(
    call: ExecutableToolCall,
    grant: MobileUseGrant?,
    packageName: String,
    locked: Boolean,
    now: Instant = Instant.now(),
): Boolean =
    grant != null && call.sessionId == grant.conversationId &&
        grant.scope.toScopeRef() == call.authorizationScopeRef && grant.scope.permitsPackage(packageName) &&
        !locked && !call.cancel.isCancelled() && now.isBefore(call.deadline)
