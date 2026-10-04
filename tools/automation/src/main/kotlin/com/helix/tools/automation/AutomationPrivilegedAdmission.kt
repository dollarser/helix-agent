package com.helix.tools.automation

import com.helix.core.policy.MobileUseGrant
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
