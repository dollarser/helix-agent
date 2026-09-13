"""Execute the actual DAO UPDATE in SQLite; stale snapshots cannot regress remote state."""
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[3]
source = (root / "core/storage/src/main/kotlin/com/helix/core/storage/dao/A2aDaos.kt").read_text()
fragment = source[source.index('"UPDATE a2a_tasks SET'):source.index('fun updateRemoteState')]
sql = ''.join(re.findall(r'"([^"\n]*)"', fragment.split('@Suppress')[0]))
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE a2a_tasks (toolCallId TEXT PRIMARY KEY, taskId TEXT, contextId TEXT, '
           'lastEventSequence INTEGER, lastEventId TEXT, state TEXT, deliveryState TEXT, updatedAtEpochMillis INTEGER)')
db.execute("INSERT INTO a2a_tasks VALUES ('call', 'task', 'context', 1, 'e1', 'WORKING', 'ACCEPTED', 10)")
parameters = dict(toolCallId='call', taskId='task', contextId='context', sequence=5, eventId='e5',
                  state='COMPLETED', deliveryState='RECONCILED', updatedAt=20,
                  expectedSequence=1, expectedUpdatedAt=10, expectedState='WORKING', expectedDeliveryState='ACCEPTED')
assert db.execute(sql, parameters).rowcount == 1
parameters.update(sequence=2, eventId='e2', state='WORKING', deliveryState='ACCEPTED', updatedAt=30)
assert db.execute(sql, parameters).rowcount == 0
assert db.execute('SELECT lastEventSequence, state FROM a2a_tasks').fetchone() == (5, 'COMPLETED')
db.close()
print('Actual A2A DAO SQL: stale update rejected, terminal state preserved.')
