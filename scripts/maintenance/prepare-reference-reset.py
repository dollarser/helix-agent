#!/usr/bin/env python3
"""Audit an offline development backup; optionally prepare a separately acknowledged reset COPY.

Never connects to ADB, modifies the input backup, deletes content files, or installs an app.
The copy loses legacy quote bindings/draft selections, not authored text or other settings.
"""
from contextlib import closing
import argparse
import os
import json
from pathlib import Path
import re
import sqlite3
import sys
import uuid

LEGACY = ('SUMMARY', 'RECENT_MESSAGES')
TERMINAL = ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
REFERENCE_COLUMNS = ('referenceSourceSessionId', 'referenceSourceSessionTitle', 'referenceSelectionKind',
                     'referenceSourceMessageIdsJson', 'referenceContentRef', 'referenceContentSha256')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def caches(directory):
    result = []
    if directory is None:
        return result
    for path in sorted(directory.iterdir()):
        require(path.is_file() and not path.is_symlink() and re.fullmatch('[a-f0-9]{64}\\.json', path.name),
                'Expected a stopped-app composer-input backup, without links or temporary files')
        require(path.stat().st_size <= 16 * 1024 * 1024, 'Cache exceeds inspection bound')
        item = json.loads(path.read_text())
        require(item.get('referenceKind') in (None, 'HISTORY', *LEGACY), 'Unknown reference format')
        result.append((path.name, item))
    return result


def prepare(database, composer=None, output=None, output_composer=None, acknowledge=False):
    require(not database.is_symlink(), 'Database backup must not be a symbolic link')
    database = database.resolve(strict=True)
    require(not any(Path(str(database) + suffix).exists() for suffix in ('-wal', '-shm', '-journal')),
            'A complete offline SQLite backup without sidecars is required')
    drafts = caches(composer)
    transformed = []
    for name, original in drafts:
        item = dict(original)
        if item.get('referenceKind') in LEGACY:
            revision = item.get('revision')
            require(type(revision) is int and 0 <= revision < (2**63 - 1) // 2, 'Invalid draft revision')
            item.update(referenceKind=None, referenceSourceSessionId=None, revision=revision + 1,
                        clientRequestId=str(uuid.uuid4()))
        transformed.append((name, item))
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as source:
        require(source.execute('PRAGMA user_version').fetchone()[0] == 1, 'Expected development schema 1')
        for table, column in (('message_reference_snapshots', 'selectionKind'),
                              ('session_inputs', 'referenceSelectionKind')):
            unknown = source.execute(f'SELECT COUNT(*) FROM {table} WHERE {column} IS NOT NULL '
                                     f'AND {column} NOT IN (?,?,?)', (*LEGACY, 'HISTORY')).fetchone()[0]
            require(unknown == 0, 'Unknown reference format in database')
        old = source.execute('SELECT COUNT(*) FROM message_reference_snapshots WHERE selectionKind IN (?,?)', LEGACY).fetchone()[0]
        inputs = source.execute('SELECT COUNT(*) FROM session_inputs WHERE referenceSelectionKind IN (?,?)', LEGACY).fetchone()[0]
        active = source.execute('SELECT COUNT(*) FROM turns WHERE state NOT IN (?,?,?,?)', TERMINAL).fetchone()[0]
        report = {'legacyQuoteBindings': old, 'legacyInputReferences': inputs,
                  'legacyDraftSelections': sum(item.get('referenceKind') in LEGACY for _, item in drafts),
                  'unsettledTurns': active, 'inputBackupModified': False, 'resetCopyPrepared': False}
        if output is None:
            require(output_composer is None and not acknowledge, 'Audit mode does not create output')
            return report
        require(acknowledge, 'Explicit acknowledgement of quote/selection loss is required')
        require(active == 0, 'Resolve unsettled turns through the app before preparing a reset')
        require((composer is None) == (output_composer is None), 'Composer backup and output must be provided together')
        require(not output.is_symlink(), 'Output must not be a symbolic link')
        output = output.resolve()
        require(output != database and not output.exists(), 'Output must be a new, separate database file')
        if output_composer is not None:
            require(not output_composer.is_symlink(), 'Composer output must not be a symbolic link')
            require(not output_composer.exists() and output_composer.resolve() != composer.resolve(),
                    'Composer output must be a separate new directory')
        # Exclusive creation protects every existing operator file, including the source backup.
        descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(descriptor)
        with closing(sqlite3.connect(output)) as target, target:
            source.backup(target)
            target.execute('PRAGMA foreign_keys=ON')
            target.execute('DELETE FROM message_reference_snapshots WHERE selectionKind IN (?,?)', LEGACY)
            assignments = ', '.join(name + '=NULL' for name in REFERENCE_COLUMNS)
            target.execute('UPDATE session_inputs SET ' + assignments + ", referenceContentBytes=0, "
                           "state=CASE WHEN state IN ('PENDING','NEEDS_ATTENTION') THEN 'NEEDS_ATTENTION' ELSE state END, "
                           "blockedReason=CASE WHEN state IN ('PENDING','NEEDS_ATTENTION') THEN 'REFERENCE_RESELECT_REQUIRED' "
                           "ELSE blockedReason END WHERE referenceSelectionKind IN (?,?)", LEGACY)
            require(not target.execute('PRAGMA foreign_key_check').fetchall(), 'Foreign-key verification failed')
            require(target.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Integrity verification failed')
        if output_composer is not None:
            output_composer.mkdir(mode=0o700)
            for name, item in transformed:
                descriptor = os.open(output_composer / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, 'w') as destination:
                    json.dump(item, destination, ensure_ascii=False)
        report['resetCopyPrepared'] = True
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--composer-input', type=Path)
    parser.add_argument('--output-database', type=Path)
    parser.add_argument('--output-composer-input', type=Path)
    parser.add_argument('--acknowledge-reference-reset', action='store_true')
    args = parser.parse_args()
    try:
        result = prepare(args.database, args.composer_input, args.output_database,
                         args.output_composer_input, args.acknowledge_reference_reset)
    except (ValueError, OSError, sqlite3.Error):
        print('Reference reset preparation failed; source backup was not modified; any output is incomplete until success.', file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
