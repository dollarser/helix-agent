#!/usr/bin/env python3
"""Fix two races reproduced by the expanded HXA-184 device baseline."""
from pathlib import Path
R=Path(__file__).resolve().parents[3]
p=R/'app/src/main/kotlin/com/helix/app/approval/StorageApprovalBroker.kt';s=p.read_text();a=s.index('        approvals.create(');b=s.index('        try {\n            cardSink',a)
s=s[:a]+'''        val wait = DecisionWaiter()
        // The row can become visible to a decision thread before create() returns. Publish the
        // wait slot under the same lock decide() takes after its durable write, so such a decision
        // cannot pass the registration gap. No database connection is held while awaiting input.
        synchronized(lock) {
            approvals.create(
                id = approvalId,
                toolCallId = request.binding.toolCallId,
                binding = request.binding,
                createdAt = now,
                expiresAt = now + ApprovalRepository.MAX_APPROVAL_TTL_MILLIS,
            )
            waits[approvalId] = wait
        }
'''+s[b:];p.write_text(s)
p=R/'app/src/main/kotlin/com/helix/app/chat/ChatService.kt';s=p.read_text();old='''        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return
        val goalId = storage.goalRuns.resolve(binding.runId).goalId
''';new='''        // Goal deletion cascades bindings and runs together. Read both in one snapshot so
        // user deletion after terminal publication cannot leave a stale binding between reads.
        val goalId = storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId) ?: return@withTransaction null
            storage.goalRuns.resolve(binding.runId).goalId
        } ?: return
''';assert old in s;s=s.replace(old,new);p.write_text(s)
p=R/'app/src/androidTest/kotlin/com/helix/app/ui/ChatStopProgressDeviceTest.kt';s=p.read_text().replace('import androidx.compose.ui.test.assertIsDisplayed','import androidx.compose.ui.test.isDisplayed\nimport androidx.compose.ui.test.assertIsDisplayed');needle='''        compose.onNodeWithTag("chat-turn-error").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertIsDisplayed()''';assert needle in s;s=s.replace(needle,'''        // The durable terminal precedes projection refresh and the timeline's follow-to-end effect.
        // Observe the real visible retry control before checking it; do not force-scroll it into view.
        compose.waitUntil(10_000) { compose.onNodeWithTag("chat-retry").isDisplayed() }
        compose.onNodeWithTag("chat-turn-error").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertIsDisplayed()''');p.write_text(s)
