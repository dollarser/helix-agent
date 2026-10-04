"""Mobile Use release-boundary checks; no Android execution or screenshot capture."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('mobile_apk_guard', ROOT / 'scripts/verify-integrated-runtime-apks.py')
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)
A = GUARD.A


class MobileUseContractTest(unittest.TestCase):
    def setUp(self):
        self.app = ET.Element('application')
        self.service = ET.SubElement(self.app, 'service', {
            A + 'name': 'com.helix.tools.automation.HelixAccessibilityService',
            A + 'exported': 'true', A + 'permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
        })
        self.metadata = ET.SubElement(self.service, 'meta-data', {
            A + 'name': 'android.accessibilityservice', A + 'resource': '@xml/helix_accessibility_service',
        })
        self.config = ET.parse(ROOT / 'tools/automation/src/main/res/xml/helix_accessibility_service.xml').getroot()

    def test_app_catalog_visibility_is_advanced_only(self):
        manifest = ET.parse(ROOT / 'app/src/developer/AndroidManifest.xml').getroot()
        GUARD.verify_mobile_app_visibility(manifest, True)
        GUARD.verify_mobile_app_visibility(ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot(), False)
        with self.assertRaises(RuntimeError):
            GUARD.verify_mobile_app_visibility(manifest, False)
        with self.assertRaises(RuntimeError):
            GUARD.verify_mobile_app_visibility(ET.Element('manifest'), True)

    def test_shizuku_provider_and_authorization_are_channel_and_caller_protected(self):
        app = ET.parse(ROOT / 'app/src/developer/AndroidManifest.xml').getroot().find('application')
        GUARD.verify_shizuku(app, True)
        GUARD.verify_shizuku(ET.Element('application'), False)
        with self.assertRaises(RuntimeError):
            GUARD.verify_shizuku(app, False)
        for tag, name in [('provider', 'rikka.shizuku.ShizukuProvider'),
                          ('activity', 'com.helix.app.automation.shizuku.ShizukuPermissionActivity')]:
            copy = ET.fromstring(ET.tostring(app))
            component = next(item for item in copy.findall(tag) if item.get(A + 'name') == name)
            if tag == 'provider':
                component.attrib.pop(A + 'permission')
            else:
                component.set(A + 'exported', 'true')
            with self.assertRaises(RuntimeError):
                GUARD.verify_shizuku(copy, True)

    def test_source_declares_all_three_native_capabilities(self):
        GUARD.verify_mobile_use(self.app, True, self.config)

    def test_consumer_cannot_contain_the_service(self):
        GUARD.verify_mobile_use(ET.Element('application'), False)
        with self.assertRaises(RuntimeError):
            GUARD.verify_mobile_use(self.app, False, self.config)

    def test_each_missing_capability_is_rejected(self):
        for name in ('canRetrieveWindowContent', 'canPerformGestures', 'canTakeScreenshot'):
            copy = ET.fromstring(ET.tostring(self.config))
            copy.set(A + name, 'false')
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                GUARD.verify_mobile_use(self.app, True, copy)

    def test_each_missing_accessibility_flag_is_rejected(self):
        required = (
            'flagReportViewIds',
            'flagRetrieveInteractiveWindows',
            'flagIncludeNotImportantViews',
        )
        for name in required:
            copy = ET.fromstring(ET.tostring(self.config))
            flags = [item for item in copy.get(A + 'accessibilityFlags', '').split('|') if item != name]
            copy.set(A + 'accessibilityFlags', '|'.join(flags))
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                GUARD.verify_mobile_use(self.app, True, copy)

    def test_compiled_numeric_accessibility_flags_are_accepted(self):
        copy = ET.fromstring(ET.tostring(self.config))
        copy.set(A + 'accessibilityFlags', '0x52')
        GUARD.verify_mobile_use(self.app, True, copy)

    def test_compiled_numeric_accessibility_flags_reject_missing_bit(self):
        copy = ET.fromstring(ET.tostring(self.config))
        copy.set(A + 'accessibilityFlags', '0x50')
        with self.assertRaises(RuntimeError):
            GUARD.verify_mobile_use(self.app, True, copy)

    def test_compiled_resource_identity_is_bound_to_actual_metadata(self):
        self.metadata.set(A + 'resource', '@ref/0x7f0d0005')
        table = ' resource 0x7f0d0005 xml/helix_accessibility_service\n'
        GUARD.verify_mobile_use(self.app, True, self.config, table)
        self.metadata.set(A + 'resource', '@ref/0x7f0d0006')
        with self.assertRaises(RuntimeError):
            GUARD.verify_mobile_use(self.app, True, self.config, table)

    def test_arbitrary_external_binder_access_is_not_accepted(self):
        self.service.attrib.pop(A + 'permission')
        with self.assertRaises(RuntimeError):
            GUARD.verify_mobile_use(self.app, True, self.config)


if __name__ == '__main__':
    unittest.main()
