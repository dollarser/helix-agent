"""Guard the conversation's explicit knowledge and read-aloud dependencies."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / 'app/src/main/kotlin/com/helix/app/ui'


class ConversationDependenciesTest(unittest.TestCase):
    def test_conversation_does_not_resolve_application_services(self):
        for name in ('ChatScreen', 'ConversationSection', 'ConversationMessage',
                     'ReadAloudAction', 'AutomaticReplyAudioControl'):
            with self.subTest(component=name):
                source = (UI / f'{name}.kt').read_text()
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
        screen = (UI / 'ChatScreen.kt').read_text()
        section = (UI / 'ConversationSection.kt').read_text()
        message = (UI / 'ConversationMessage.kt').read_text()
        self.assertIn('consent = speechConsent', screen)
        self.assertIn('accountIdentity = accountIdentity', screen)
        self.assertIn('accountStatus = accountStatus', screen)
        self.assertIn('ReadAloudLifecycle(reader, screen.openSessionId)', section)
        self.assertIn('ObserveLiveReplyReadAloud(reader, speechFrame)', section)
        self.assertIn('playback = { playback(speechFrame) }', section)
        self.assertIn('readAloudAction = { readAloudAction(message) }', section)
        self.assertIn('readAloudAction = readAloudAction', message)
        self.assertIn('readAloudAction()', message)


if __name__ == '__main__':
    unittest.main()
