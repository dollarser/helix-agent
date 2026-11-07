"""Guard the conversation's explicit knowledge and read-aloud dependencies."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / 'app/src/main/kotlin/com/helix/app/ui'
PRESENTATION = ROOT / 'feature/conversation/src/main/kotlin/com/helix/feature/conversation/presentation'


class ConversationDependenciesTest(unittest.TestCase):
    def test_conversation_does_not_resolve_application_services(self):
        for path in (UI / 'AppConversationRoute.kt', PRESENTATION / 'ChatScreen.kt',
                     PRESENTATION / 'ConversationMessage.kt', UI / 'ReadAloudAction.kt',
                     UI / 'AutomaticReplyAudioControl.kt'):
            with self.subTest(component=path.stem):
                source = path.read_text()
                self.assertNotIn('HelixApplication', source)
                self.assertNotIn('applicationContext', source)
                self.assertNotIn('AppContainer', source)

    def test_remaining_settings_and_voice_services_are_explicit(self):
        for path in UI.glob('*.kt'):
            self.assertNotIn('HelixApplication', path.read_text(), path.name)
        source = (ROOT / 'app/src/main/kotlin/com/helix/app/voice/SpeechSettingsStore.kt').read_text()
        self.assertNotIn('HelixApplication', source)
        self.assertRegex(source, r'account:\s+(?:com\.helix\.app\.account\.)?AccountSession')
        self.assertNotIn('fun AccountSettingsEntry(', (UI / 'AccountSettings.kt').read_text())

    def test_shell_supplies_knowledge_and_audio_dependencies(self):
        source = (ROOT / 'app/src/main/kotlin/com/helix/app/MainActivity.kt').read_text()
        for argument in ('knowledge = application.knowledge.references',
                         'reader = application.readAloud',
                         'speechConsent = application.speechConsent',
                         'replyAudioSettings = application.replyAudioSettings',
                         'accountIdentity = accountIdentity', 'accountStatus = accountStatus'):
            self.assertIn(argument, source)

    def test_account_changes_resolve_current_reader_and_restart_audio_observation(self):
        shell = (ROOT / 'app/src/main/kotlin/com/helix/app/MainActivity.kt').read_text()
        self.assertIn('application.account.identityState.collectAsStateWithLifecycle()', shell)
        self.assertIn('application.account.status.collectAsStateWithLifecycle()', shell)
        automatic = (UI / 'AutomaticReplyAudioControl.kt').read_text()
        self.assertIn('LaunchedEffect(reader, automatic, frame, accountReady, approved, revision, visible)', automatic)
        action = (UI / 'ReadAloudAction.kt').read_text()
        self.assertIn('LaunchedEffect(accountIdentity, service)', action)

    def test_audio_state_and_actions_reach_message_and_playback_slots(self):
        screen = (UI / 'AppConversationRoute.kt').read_text()
        section = (PRESENTATION / 'ChatScreen.kt').read_text()
        message = (PRESENTATION / 'ConversationMessage.kt').read_text()
        self.assertIn('consent = speechConsent', screen)
        self.assertIn('accountIdentity = accountIdentity', screen)
        self.assertIn('accountStatus = accountStatus', screen)
        self.assertIn('ReadAloudLifecycle(reader, screen.openSessionId)', screen)
        self.assertIn('ObserveLiveReplyReadAloud(reader, speechFrame)', screen)
        self.assertIn('playback = playback', section)
        self.assertIn('playback = {', screen)
        self.assertIn('val frame = speechFrame', screen)
        for dependency in ('ReadAloud', 'SpeechSettings', 'AccountSession', 'KnowledgeReferenceService'):
            self.assertNotIn(dependency, section)
        self.assertIn('readAloudAction = { readAloudAction(message) }', section)
        self.assertIn('readAloudAction = readAloudAction', message)
        self.assertIn('readAloudAction()', message)


if __name__ == '__main__':
    unittest.main()
