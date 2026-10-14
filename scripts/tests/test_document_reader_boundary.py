"""HXA-284 packaged process boundary, no device access."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('pdf_apk_guard', ROOT / 'scripts/verify-integrated-runtime-apks.py')
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)
A = GUARD.A


class DocumentReaderBoundaryTest(unittest.TestCase):
    def test_both_channels_require_an_isolated_non_exported_parser(self):
        for package in ('com.helix.agent', 'com.helix.agent.developer'):
            for process in (':document_reader', package + ':document_reader'):
                app = ET.Element('application')
                service = ET.SubElement(app, 'service', {A + 'name': 'com.helix.app.documents.DocumentReaderService',
                    A + 'exported': 'false', A + 'isolatedProcess': 'true', A + 'process': process})
                GUARD.verify_document_reader(app, package)
                for key, wrong in (('exported', 'true'), ('isolatedProcess', 'false'), ('process', package)):
                    original = service.get(A + key)
                    service.set(A + key, wrong)
                    with self.assertRaises(RuntimeError):
                        GUARD.verify_document_reader(app, package)
                    service.set(A + key, original)
                app.remove(service)
                with self.assertRaises(RuntimeError):
                    GUARD.verify_document_reader(app, package)

    def test_source_manifest_declares_the_same_boundary(self):
        app = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().find('application')
        service = app.find("service[@" + A + "name='.documents.DocumentReaderService']")
        service.set(A + 'name', 'com.helix.app.documents.DocumentReaderService')
        GUARD.verify_document_reader(app, 'com.helix.agent')


if __name__ == '__main__':
    unittest.main()
