from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'maintenance' / 'prepare-reference-reset.py'
spec = importlib.util.spec_from_file_location('reference_reset', SCRIPT)
reset = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reset)


class ReferenceResetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db = self.root / 'backup.db'
        with closing(sqlite3.connect(self.db)) as db, db:
            db.executescript('''PRAGMA user_version=1;
                CREATE TABLE turns(state TEXT);
                CREATE TABLE message_reference_snapshots(selectionKind TEXT);
                CREATE TABLE session_inputs(referenceSourceSessionId TEXT, referenceSourceSessionTitle TEXT,
                referenceSelectionKind TEXT, referenceSourceMessageIdsJson TEXT, referenceContentRef TEXT,
                referenceContentSha256 TEXT, referenceContentBytes INTEGER, state TEXT, blockedReason TEXT,
                textRef TEXT, configurationFingerprint TEXT);
                CREATE TABLE settings(value TEXT);
                INSERT INTO settings VALUES('untouched account/model/plugin/expert facts');
                INSERT INTO turns VALUES('COMPLETED');
                INSERT INTO message_reference_snapshots VALUES('RECENT_MESSAGES'),('HISTORY');
                INSERT INTO session_inputs VALUES('source','Title','SUMMARY','[]','old-ref','hash',42,
                'PENDING',NULL,'authored-text-ref','original-config');''')
        self.composer = self.root / 'composer'
        self.composer.mkdir()
        self.name = 'a' * 64 + '.json'
        self.draft = {'referenceKind': 'RECENT_MESSAGES', 'referenceSourceSessionId': 'source', 'revision': 8,
                      'clientRequestId': 'old-request', 'text': 'authored draft', 'attachmentIds': ['keep-file']}
        (self.composer / self.name).write_text(json.dumps(self.draft))

    def test_audit_is_read_only_and_does_not_echo_content(self):
        before = self.db.read_bytes()
        result = reset.prepare(self.db, self.composer)
        self.assertEqual(1, result['legacyQuoteBindings'])
        self.assertEqual(1, result['legacyDraftSelections'])
        self.assertEqual(before, self.db.read_bytes())
        self.assertNotIn('authored draft', json.dumps(result))
        self.assertEqual(self.draft, json.loads((self.composer / self.name).read_text()))

    def test_copy_resets_only_legacy_references_and_parks_pending_inputs(self):
        before = self.db.read_bytes()
        out = self.root / 'review.db'
        cache_out = self.root / 'review-composer'
        result = reset.prepare(self.db, self.composer, out, cache_out, True)
        self.assertTrue(result['resetCopyPrepared'])
        self.assertEqual(before, self.db.read_bytes())
        with closing(sqlite3.connect(out)) as db:
            self.assertEqual([('HISTORY',)], db.execute('SELECT * FROM message_reference_snapshots').fetchall())
            row = db.execute('SELECT referenceSelectionKind,state,blockedReason,textRef,configurationFingerprint FROM session_inputs').fetchone()
            self.assertEqual((None, 'NEEDS_ATTENTION', 'REFERENCE_RESELECT_REQUIRED', 'authored-text-ref', 'original-config'), row)
            self.assertEqual('untouched account/model/plugin/expert facts', db.execute('SELECT value FROM settings').fetchone()[0])
        draft = json.loads((cache_out / self.name).read_text())
        self.assertEqual('authored draft', draft['text'])
        self.assertEqual(['keep-file'], draft['attachmentIds'])
        self.assertIsNone(draft['referenceKind'])
        self.assertEqual(9, draft['revision'])
        self.assertNotEqual('old-request', draft['clientRequestId'])

    def test_live_or_ambiguous_state_and_existing_outputs_are_rejected(self):
        with self.assertRaises(ValueError):
            reset.prepare(self.db, output=self.db, acknowledge=True)
        with self.assertRaises(ValueError):
            reset.prepare(self.db, output=self.root / 'new.db')
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("UPDATE turns SET state='NEEDS_REVIEW'")
        with self.assertRaises(ValueError):
            reset.prepare(self.db, output=self.root / 'new.db', acknowledge=True)
        self.assertFalse((self.root / 'new.db').exists())

    def test_unknown_formats_and_invalid_drafts_fail_before_output(self):
        out = self.root / 'review.db'
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("INSERT INTO message_reference_snapshots VALUES('UNKNOWN')")
        with self.assertRaises(ValueError):
            reset.prepare(self.db, output=out, acknowledge=True)
        self.assertFalse(out.exists())
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("DELETE FROM message_reference_snapshots WHERE selectionKind='UNKNOWN'")
        self.draft['revision'] = -1
        (self.composer / self.name).write_text(json.dumps(self.draft))
        with self.assertRaises(ValueError):
            reset.prepare(self.db, self.composer, out, self.root / 'new-composer', True)
        self.assertFalse(out.exists())

    def test_dangling_output_symlink_is_not_followed(self):
        out = self.root / 'review.db'
        destination = self.root / 'unintended.db'
        out.symlink_to(destination)
        with self.assertRaises(ValueError):
            reset.prepare(self.db, output=out, acknowledge=True)
        self.assertFalse(destination.exists())
