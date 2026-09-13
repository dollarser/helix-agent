import argparse, subprocess, sqlite3
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();assert not a.serial.startswith('emulator-');a.output.mkdir(exist_ok=False,parents=True)
for name in ['helix.db','helix.db-wal']:
 (a.output/name).write_bytes(subprocess.check_output(['adb','-s',a.serial,'exec-out','run-as','com.helix.agent','cat','databases/'+name]))
c=sqlite3.connect(a.output/'helix.db');c.row_factory=sqlite3.Row
for table in ['turns','messages']:
 try:
  rows=[dict(r) for r in c.execute('SELECT * FROM '+table)]
  print(table, rows)
 except sqlite3.Error as e: print(e)
