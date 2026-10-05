"""HXA-242 source/package guard regressions; no network or device access."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('http_apk_guard', ROOT / 'scripts/verify-integrated-runtime-apks.py')
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)
A = GUARD.A


class ProviderHttpPolicyTest(unittest.TestCase):
    def test_packaged_xml_paths_support_release_names_and_reject_ambiguity(self):
        for path in ('res/xml/network_security_config.xml', 'res/8G.xml'):
            table = f'    resource 0x7f0d0002 xml/network_security_config\n      () (file) {path} type=XML\n'
            self.assertEqual(path, GUARD.packaged_xml_path(table, 'network_security_config'))
            for wrong in ('', table + table, table.replace('network_security_config', 'file_paths'),
                          table + '      (night) (file) res/other.xml type=XML\n',
                          table.replace(path, '../outside.xml')):
                with self.subTest(table=wrong), self.assertRaises(RuntimeError):
                    GUARD.packaged_xml_path(wrong, 'network_security_config')

    def app(self):
        return ET.Element('application', {A + 'networkSecurityConfig': '@xml/network_security_config'})

    def test_shared_configuration_allows_http_and_keeps_default_trust(self):
        config = ET.parse(ROOT / 'app/src/main/res/xml/network_security_config.xml').getroot()
        app = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().find('application')
        GUARD.verify_http_network_config(app, config)
        self.assertFalse((ROOT / 'app/src/developer/res/xml/network_security_config.xml').exists())
        self.assertFalse((ROOT / 'app/src/consumer/res/xml/network_security_config.xml').exists())

    def test_missing_reference_and_transport_denial_are_rejected(self):
        allowed = ET.fromstring('<network-security-config><base-config cleartextTrafficPermitted="true"/></network-security-config>')
        with self.assertRaises(RuntimeError):
            GUARD.verify_http_network_config(ET.Element('application'), allowed)
        for xml in ('<network-security-config/>', '<network-security-config><base-config cleartextTrafficPermitted="false"/></network-security-config>'):
            with self.subTest(xml=xml), self.assertRaises(RuntimeError):
                GUARD.verify_http_network_config(self.app(), ET.fromstring(xml))

    def test_unreviewed_trust_or_domain_overrides_are_rejected(self):
        for child in ('<trust-anchors><certificates src="user"/></trust-anchors>', '<domain-config/>'):
            xml = '<network-security-config><base-config cleartextTrafficPermitted="true">' + child + '</base-config></network-security-config>'
            with self.subTest(child=child), self.assertRaises(RuntimeError):
                GUARD.verify_http_network_config(self.app(), ET.fromstring(xml))

    def test_compiled_resource_id_must_resolve_to_the_exact_network_config(self):
        app = ET.Element('application', {A + 'networkSecurityConfig': '@ref/0x7f0d0002'})
        config = ET.fromstring('<network-security-config><base-config cleartextTrafficPermitted="true"/></network-security-config>')
        table = '    resource 0x7f0d0002 xml/network_security_config\n'
        GUARD.verify_http_network_config(app, config, table)
        for wrong in ('', table.replace('0002', '0003'), table.replace('network_security_config', 'file_paths'), table + table):
            with self.subTest(table=wrong), self.assertRaises(RuntimeError):
                GUARD.verify_http_network_config(app, config, wrong)

    def test_retired_http_gates_are_absent_from_provider_and_send_paths(self):
        paths = ('app/src/main/kotlin/com/helix/app/provider/ProviderService.kt',
                 'app/src/main/kotlin/com/helix/app/provider/ProviderDraftDiscovery.kt',
                 'app/src/main/kotlin/com/helix/app/chat/ChatService.kt',
                 'app/src/main/kotlin/com/helix/app/chat/SessionInputDeliveryCoordinator.kt',
                 'app/src/main/kotlin/com/helix/app/ui/ProviderFormDialog.kt')
        for path in paths:
            text = (ROOT / path).read_text()
            for gate in ('isCleartextPermitted', 'cleartextConfirmed', 'CleartextBindingStore'):
                with self.subTest(path=path, gate=gate):
                    self.assertNotIn(gate, text)

    def test_warnings_are_localized_and_tls_downgrade_is_disabled(self):
        for locale in ('values', 'values-en', 'values-zh-rCN'):
            root = ET.parse(ROOT / f'app/src/main/res/{locale}/strings.xml').getroot()
            strings = {node.get('name'): node.text for node in root.findall('string')}
            self.assertTrue(strings.get('chat_cleartext_warning'))
            self.assertTrue(strings.get('provider_cleartext_warning'))
            self.assertNotIn('provider_cleartext_confirm_required', strings)
        transport = (ROOT / 'provider/api/src/main/kotlin/com/helix/provider/api/wire/OkHttpWireClient.kt').read_text()
        self.assertIn('.followSslRedirects(false)', transport)


if __name__ == '__main__':
    unittest.main()
