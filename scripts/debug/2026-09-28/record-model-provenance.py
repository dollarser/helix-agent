import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[3]
out=root/'build/hxa222-real-model'
entry=next(x for x in json.loads((out/'modelscope.json').read_text())['Data']['Files'] if x['Name']=='Qwen3-0.6B-Q4_K_M.gguf')
p=out/entry['Name']; sha=hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
assert sha==entry['Sha256'] and p.stat().st_size==entry['Size']
(out/'model.json').write_text(json.dumps({'repository':'unsloth/Qwen3-0.6B-GGUF','mirror':'https://modelscope.cn/models/unsloth/Qwen3-0.6B-GGUF','revision':entry['Revision'],'filename':entry['Name'],'sha256':sha,'bytes':p.stat().st_size,'ggufContext':40960,'testedContext':32768,'threads':2,'reasoning':'OFF','sampling':'greedy'},indent=2)+'\n')
