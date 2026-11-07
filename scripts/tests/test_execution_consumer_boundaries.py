"""Keep native/background consumers on explicit capabilities, not the conversation facade."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/kotlin/com/helix/app'


class ExecutionConsumerBoundariesTest(unittest.TestCase):
    def test_background_and_plugin_hosts_do_not_depend_on_chat_facade(self):
        for name in ('scheduled/RuntimeScheduledAgentExecution.kt', 'plugin/PluginTaskHostAdapter.kt'):
            source = (APP / name).read_text()
            self.assertNotIn('ChatService', source)
            self.assertNotIn('HelixApplication', source)

    def test_submission_preparation_reads_narrow_facts(self):
        source = (APP / 'application/agent/TurnSubmissionCommandHandler.kt').read_text()
        for dependency in ('HelixStorage', 'ProviderService', 'com.helix.app.infrastructure.'):
            self.assertNotIn(dependency, source)
        self.assertIn('SubmissionAdmissionFacts', source)
        self.assertIn('SubmissionProviderSnapshots', source)

    def test_execution_is_constructed_by_the_composition_root(self):
        facade = (APP / 'chat/ChatService.kt').read_text()
        composition = (APP / 'composition/ConversationComposition.kt').read_text()
        for constructor in ('AppAgentRuntime(', 'AgentLoopHost(', 'ApplicationToolGateway(',
                            'TurnSubmissionCommandHandler(', 'StopTurnCommandHandler('):
            self.assertNotIn(constructor, facade)
            self.assertEqual(1, composition.count(constructor), constructor)
        self.assertNotIn('createRoomTurnEngine(', facade)
        self.assertIn('internal val agentRuntime: AgentRuntime', facade)
        self.assertIn('agentRuntime = agentRuntime', composition)
        self.assertIn('turnEngine = turnEngine', composition)
        self.assertIn('started.compareAndSet(false, true)', facade)
        self.assertTrue((APP / 'application/agent/AppAgentRuntime.kt').is_file())
        self.assertFalse((APP / 'chat/AppAgentRuntime.kt').exists())

    def test_admission_and_input_contracts_do_not_depend_on_storage(self):
        for name in ('TurnSubmissionCommandHandler', 'SubmissionAdmissionFacts',
                     'SessionInputConfiguration', 'TurnInputFingerprint'):
            self.assertNotIn('com.helix.core.storage',
                             (APP / f'application/agent/{name}.kt').read_text())
        for path in (ROOT / 'core/model/src/main/kotlin/com/helix/core/model/input').glob('*.kt'):
            self.assertNotIn('com.helix.core.storage', path.read_text(), str(path))
        storage = (ROOT / 'core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionInputModels.kt').read_text()
        for name in ('SessionInputDelivery', 'InputAttachment', 'InputConfiguration', 'SessionInputSpec'):
            self.assertNotRegex(storage, rf'(?:class|interface)\s+{name}\b')

    def test_prompt_mapping_does_not_read_services(self):
        source = (APP / 'application/context/SystemPromptContext.kt').read_text()
        for dependency in ('HelixStorage', 'MemoryService', 'contextSnapshot(', 'projectInstructionsReader'):
            self.assertNotIn(dependency, source)
        self.assertIn('facts.request == request', source)

    def test_container_receives_account_and_knowledge_and_starts_once(self):
        source = (APP / 'DefaultAppContainer.kt').read_text()
        self.assertNotRegex(source, r'as\?? HelixApplication')
        self.assertIn('started.compareAndSet(false, true)', source)
        application = (APP / 'HelixApplication.kt').read_text()
        self.assertIn('DefaultAppContainer(this, account, knowledge).also { it.start() }', application)


if __name__ == '__main__':
    unittest.main()
