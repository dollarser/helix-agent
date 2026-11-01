package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotToolBridgeEndpoint
import java.io.File

/** Independent tiny SDKs. Credentials live only in the execution environment, never these files. */
internal object ProotScriptTools {
    fun prepare(
        tmp: File,
        endpoint: ProotToolBridgeEndpoint,
        environment: MutableMap<String, String>,
        deadline: Long,
    ) {
        File(tmp, "helix.py").writeText(PYTHON)
        File(tmp, "helix.mjs").writeText(NODE)
        environment["HELIX_TOOLS_SOCKET"] = endpoint.socketName
        environment["HELIX_TOOLS_PROOF"] = endpoint.proof
        environment["HELIX_TOOLS_DEADLINE"] = deadline.toString()
        environment["PYTHONPATH"] = listOfNotNull("/tmp", environment["PYTHONPATH"]).joinToString(":")
    }

    private val PYTHON =
        """
        import json, os, socket, time, uuid
        def call(tool, args=None):
            body = json.dumps(dict(proof=os.environ['HELIX_TOOLS_PROOF'], id=uuid.uuid4().hex,
                tool=tool, args={} if args is None else args), separators=(',',':')).encode() + b'\n'
            if len(body) > 262144: raise ValueError('BRIDGE_REQUEST_TOO_LARGE')
            remaining = float(os.environ['HELIX_TOOLS_DEADLINE']) / 1000 - time.time()
            if remaining <= 0: raise RuntimeError('BRIDGE_EXPIRED')
            try:
                with socket.socket(socket.AF_UNIX) as channel:
                    channel.settimeout(remaining)
                    channel.connect('\0' + os.environ['HELIX_TOOLS_SOCKET'])
                    channel.sendall(body)
                    output = bytearray()
                    while len(output) <= 262144:
                        chunk = channel.recv(min(65536, 262145 - len(output)))
                        if not chunk: raise RuntimeError('BRIDGE_RESULT_UNKNOWN_DO_NOT_REPLAY')
                        output.extend(chunk)
                        if output.endswith(b'\n'): return json.loads(output)
                    raise RuntimeError('BRIDGE_RESULT_TOO_LARGE_DO_NOT_REPLAY')
            except OSError:
                raise RuntimeError('BRIDGE_TRANSPORT_UNKNOWN_DO_NOT_REPLAY') from None
        """.trimIndent()

    private val NODE =
        """
        import net from 'node:net';
        import {randomUUID} from 'node:crypto';
        export function call(tool,args={}) {
          const id=randomUUID().replaceAll('-','');
          const body=JSON.stringify({proof:process.env.HELIX_TOOLS_PROOF,id,tool,args})+'\n';
          if(Buffer.byteLength(body)>262144) throw new Error('BRIDGE_REQUEST_TOO_LARGE');
          const remaining=Number(process.env.HELIX_TOOLS_DEADLINE)-Date.now();
          if(!(remaining>0)) throw new Error('BRIDGE_EXPIRED');
          return new Promise((resolve,reject)=>{
            const socket=net.createConnection({path:'\0'+process.env.HELIX_TOOLS_SOCKET});
            let chunks=[],size=0,done=false;
            const fail=()=>{if(!done){done=true;socket.destroy();
              reject(new Error('BRIDGE_RESULT_UNKNOWN_DO_NOT_REPLAY'));}};
            socket.setTimeout(remaining,fail);
            socket.on('connect',()=>socket.write(body));
            socket.on('error',fail);socket.on('end',fail);
            socket.on('data',chunk=>{
              size+=chunk.length;if(size>262144){fail();return;}chunks.push(chunk);
              if(chunk[chunk.length-1]===10){
                try{const result=JSON.parse(Buffer.concat(chunks).toString('utf8'));
                  done=true;socket.destroy();resolve(result);}
                catch{fail();}
              }
            });
          });
        }
        """.trimIndent()
}
