import json
import struct
from pathlib import Path
root=Path(__file__).resolve().parents[3]
p=root/'build/hxa222-real-model/Qwen3-0.6B-Q4_K_M.gguf'
f=p.open('rb')
def unpack(fmt): return struct.unpack('<'+fmt, f.read(struct.calcsize('<'+fmt)))[0]
def string(): return f.read(unpack('Q')).decode()
def value(t):
    if t==8: return string()
    if t==9:
        subtype,count=unpack('I'),unpack('Q')
        return [value(subtype) for _ in range(count)]
    return unpack({0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}[t])
assert f.read(4)==b'GGUF'
version,tensors,fields=unpack('I'),unpack('Q'),unpack('Q')
record={'version':version,'tensors':tensors}
for _ in range(fields):
    key=string(); val=value(unpack('I'))
    if key.startswith('general.') or key.endswith(('context_length','embedding_length','block_count','head_count_kv')):
        record[key]=val
(root/'build/hxa222-real-model/gguf-metadata.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record,indent=2))
