"""Read only the current test app's own policy; never mutate manager data."""
import re
import shlex
import subprocess
import sys
serial = sys.argv[1]
adb = ['adb', '-s', serial]
package = 'com.helix.agent.developer'
rows = subprocess.check_output(adb + ['shell', 'pm', 'list', 'packages', '-U', package], text=True)
match = re.search(r'^package:' + re.escape(package) + r' uid:(\d+)$', rows, re.M)
assert match, rows
query = 'SELECT uid,policy,until FROM policies WHERE uid=' + match[1] + ';'
command = 'su -c ' + shlex.quote('magisk --sqlite ' + shlex.quote(query))
subprocess.run(adb + ['shell', command], check=True, timeout=10)
