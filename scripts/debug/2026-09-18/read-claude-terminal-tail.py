#!/usr/bin/env python3
"""Read a bounded, ANSI-stripped terminal tail from one explicitly selected Claude background job."""
import re
import subprocess
import sys

result = subprocess.run(["claude", "logs", sys.argv[1]], capture_output=True, text=True, check=True)
text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", result.stdout[-60000:])
print(text.replace("\r", "\n")[-7000:])
