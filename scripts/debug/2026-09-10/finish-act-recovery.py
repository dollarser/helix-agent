"""Update localized network guidance; apply only the targeted bug-fix edits."""
from pathlib import Path
import re

for locale in ['values', 'values-zh-rCN', 'values-en']:
    path = Path('app/src/main/res') / locale / 'strings.xml'
    text = path.read_text()
    labels = {
        'conn_error_transport': '网络/TLS 连接失败。请检查网络、DNS 或代理；恢复后可继续发送消息。',
        'conn_error_timeout': '连接或响应超时。请检查网络后重试；已执行的工具不会自动重放。',
    } if locale != 'values-en' else {
        'conn_error_transport': 'Network or TLS connection failed. Check your network, DNS or proxy; you can send another message after recovery.',
        'conn_error_timeout': 'Connection or response timed out. Check your network before retrying; executed tools are not automatically replayed.',
    }
    for key, value in labels.items():
        text, count = re.subn(r'(<string name="' + key + r'">).*?(</string>)', lambda m: m[1] + value + m[2], text)
        assert count == 1
    path.write_text(text)
