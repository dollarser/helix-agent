"""Read-only experiment client. A document RPC is deliberately not a POSIX directory mount."""
import json
import socket
import sys

name, nonce, operation, path = sys.argv[1:5]
request = {'nonce': nonce, 'operation': operation, 'path': path}
if len(sys.argv) > 5:
    request['expectedHash'] = sys.argv[5]
with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
    connection.settimeout(5)
    connection.connect('\0' + name)
    connection.sendall(json.dumps(request).encode('utf-8') + b'\n')
    response = connection.makefile('rb').readline(262145)
    if len(response) > 262144:
        raise ValueError('bridge response limit')
    result = json.loads(response)
    print(json.dumps(result))
    if result.get('status') != 'ok':
        sys.exit(2)
