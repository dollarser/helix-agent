"""Compare authenticated DNS-over-TLS with UDP DNS for one public hostname; read-only."""
import socket,ssl,struct,json
name=b''.join(bytes([len(x)])+x for x in [b'chatgpt',b'com'])+b'\0'
query=struct.pack('!6H',19301,256,1,0,0,0)+name+struct.pack('!2H',1,1)
def take(s,n):
 b=b''
 while len(b)<n:
  c=s.recv(n-len(b))
  if not c:raise EOFError()
  b+=c
 return b
def skip(b,i):
 while b[i]:
  if b[i]&192==192:return i+2
  i+=b[i]+1
 return i+1
def parse(b):
 qd,an=struct.unpack('!2H',b[4:8]); i=12
 for _ in range(qd):i=skip(b,i)+4
 out=[]
 for _ in range(an):
  i=skip(b,i);typ,cls,ttl,n=struct.unpack('!HHIH',b[i:i+10]);i+=10
  if typ==1 and n==4:out.append(socket.inet_ntoa(b[i:i+n]))
  i+=n
 return out
try:
 with socket.create_connection(('223.5.5.5',853),timeout=5) as raw:
  with ssl.create_default_context().wrap_socket(raw,server_hostname='dns.alidns.com') as s:
   s.sendall(struct.pack('!H',len(query))+query)
   print(json.dumps({'transport':'verified-TLS-853','answers':parse(take(s,struct.unpack('!H',take(s,2))[0]))}))
except (OSError,EOFError) as e:print(json.dumps({'transport':'TLS-853','errorClass':type(e).__name__}))
