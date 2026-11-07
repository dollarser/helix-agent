"""Guard independently compiled conversation state and its production wiring.

The behavioral proof is :feature:conversation:testDebugUnitTest, whose
classpath deliberately contains neither the app nor durable storage.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'feature/conversation'
PACKAGE = 'com.helix.feature.conversation.input'


class ConversationInputBoundaryTest(unittest.TestCase):
    def test_module_depends_on_neutral_contracts_and_shared_presentation(self):
        build = (ROOT / 'build.gradle.kts').read_text()
        dependencies = re.search(
            r'":feature:conversation" to\s+listOf\(([^)]*)\)', build)
        self.assertIsNotNone(dependencies)
        projects = set(re.findall(r'"([^"]+)"', dependencies.group(1)))
        self.assertIn(':core:model', projects)
        self.assertIn(':ui:shared', projects)
        self.assertFalse(projects.intersection({':app', ':infrastructure:storage', ':core:storage'}))
        self.assertTrue(all(p.startswith((':core:', ':provider:', ':ui:')) for p in projects))
        self.assertIn('":feature:conversation",', (ROOT / 'settings.gradle.kts').read_text())
        self.assertIn('implementation(project(":feature:conversation"))',
                      (ROOT / 'app/build.gradle.kts').read_text())

    def test_state_machine_and_tests_have_no_app_or_storage_references(self):
        sources = list((MODULE / 'src').rglob('*.kt'))
        self.assertGreaterEqual(len(sources), 4)
        for path in sources:
            with self.subTest(source=path.relative_to(ROOT)):
                source = path.read_text()
                self.assertIn('package com.helix.feature.conversation', source)
                self.assertNotIn('com.helix.app', source)
                self.assertNotIn('com.helix.core.storage', source)
                self.assertNotIn('androidx.room', source)

    def test_app_uses_moved_state_machine_and_receipt_contract(self):
        screen = (ROOT / 'app/src/main/kotlin/com/helix/app/ui/AppConversationRoute.kt').read_text()
        service = (MODULE / 'src/main/kotlin/com/helix/feature/conversation/application/ChatService.kt').read_text()
        self.assertIn(f'import {PACKAGE}.ConversationDraftBuffer', screen)
        self.assertTrue(f'import {PACKAGE}.ChatSubmissionReceipt' in service,
                        'ChatService must consume the feature receipt contract')
        self.assertFalse((ROOT / 'app/src/main/kotlin/com/helix/app/ui/ConversationDraftBuffer.kt').exists())
        self.assertFalse((ROOT / 'app/src/main/kotlin/com/helix/app/chat/ChatSubmission.kt').exists())

    def test_queue_ui_consumes_only_pending_presentation_values(self):
        for name in ('SessionInputQueuePanel', 'SessionInputQueuePreview', 'SessionInputQueueActions'):
            source = (MODULE / f'src/main/kotlin/com/helix/feature/conversation/presentation/{name}.kt').read_text()
            self.assertNotIn('com.helix.core.storage', source, name)
            self.assertIn('PendingInputView', source, name)
        service = (MODULE / 'src/main/kotlin/com/helix/feature/conversation/application/ChatService.kt').read_text()
        self.assertIn('fun pendingInputViews(', service)
        self.assertIn('inputCommands.pendingViews(sessionId)', service)
        adapter = (ROOT / 'app/src/main/kotlin/com/helix/app/infrastructure/chat/RoomSessionInputCommands.kt').read_text()
        self.assertIn('mapNotNull(SessionInputRecord::toPendingInputView)', adapter)
        self.assertNotIn('SessionInputRecord', service)
        self.assertNotIn('fun sessionInputDeliveryStatus(', service)

    def test_storage_adaptation_stays_outside_input_module(self):
        mapper = (ROOT / 'app/src/main/kotlin/com/helix/app/infrastructure/conversation/ChatSubmissionStorageMapper.kt').read_text()
        self.assertIn('ComposerInputSnapshot', mapper)
        self.assertIn(f'import {PACKAGE}.ChatSubmission', mapper)
        codec = (ROOT / 'core/model/src/main/kotlin/com/helix/core/model/input/KnowledgeReferenceCodec.kt').read_text()
        self.assertNotIn('com.helix.core.storage', codec)
        references = (ROOT / 'core/model/src/main/kotlin/com/helix/core/model/input/KnowledgeReferences.kt').read_text()
        self.assertNotIn('fun encode(', references)
        self.assertNotIn('fun decode(', references)
        self.assertIn('KnowledgeReferenceCodec.encode(', references)
        self.assertNotIn('com.helix.core.storage', references)
        self.assertNotIn('FileContentStore', references)


if __name__ == '__main__':
    unittest.main()
