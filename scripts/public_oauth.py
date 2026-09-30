"""Narrow owner-approved public-client exception; never suppress user credentials."""
import hashlib
import json
import re
from pathlib import Path


def sanitize(path, data):
    allowed = json.loads(Path(__file__).with_name('public-oauth-allowlist.json').read_text())
    hashes = allowed.get(path, [])
    if not hashes:
        return data

    def replace(match):
        value = match.group(1)
        if hashlib.sha256(value).hexdigest() in hashes:
            return match.group().replace(value, b'approved-public-client')
        return match.group()

    return re.sub(rb'(?m)^val upstreamAntigravityClient(?:Id|Secret) = "([^"\r\n]+)"$', replace, data)
