import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from emulator_input import enable_hardware_keyboard, enable_onscreen_keyboard


class EmulatorInputTest(unittest.TestCase):
    def test_keyboard_enabled_without_changing_other_avd_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            avd = root / 'fixture.avd'
            avd.mkdir()
            config = avd / 'config.ini'
            config.write_text('hw.ramSize=2048\nhw.keyboard=no\nhw.keyboard.lid=yes\n')
            enable_hardware_keyboard('fixture', root)
            expected = 'hw.ramSize=2048\nhw.keyboard=yes\nhw.keyboard.lid=yes\n'
            self.assertEqual(config.read_text(), expected)
            enable_hardware_keyboard('fixture', root)
            self.assertEqual(config.read_text(), expected)

    def test_custom_avd_location_and_missing_keyboard_option(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            custom = root / 'custom'
            custom.mkdir()
            (root / 'fixture.ini').write_text('path=' + str(custom) + '\n')
            (custom / 'config.ini').write_text('hw.ramSize=2048\n')
            self.assertEqual(enable_hardware_keyboard('fixture', root), custom / 'config.ini')
            self.assertIn('hw.keyboard=yes\n', (custom / 'config.ini').read_text())

    def test_invalid_or_missing_avd_is_not_created(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                enable_hardware_keyboard('../other', tmp)
            with self.assertRaises(FileNotFoundError):
                enable_hardware_keyboard('missing', tmp)
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_boot_configuration_keeps_ime_visible_with_host_keyboard(self):
        device = Mock()
        enable_onscreen_keyboard(device)
        device.assert_called_once_with('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
