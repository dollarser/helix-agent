"""One-time HXA-187 accepted-binding ownership fix; exact pre-fix boundary required."""
from pathlib import Path
p=Path('runtime/quickjs/src/main/kotlin/com/helix/runtime/quickjs/JsExecutionClient.kt')
s=p.read_text();start=s.index('        val startedAt = System.nanoTime()',s.index('private fun bindInstance'))
end=s.index('        return BoundInstance(binder, connection, dead, deathRecipient)',start)+len('        return BoundInstance(binder, connection, dead, deathRecipient)')
block=s[start:end].replace('        return BoundInstance(binder, connection, dead, deathRecipient)','        val instance = BoundInstance(binder, connection, dead, deathRecipient)\n        handedOff = true\n        return instance')
block='\n'.join('    '+line if line else line for line in block.splitlines())
s=s[:start]+'''        var handedOff = false
        try {
'''+block+'''
        } finally {
            // execute() cannot release a binding until bindInstance returns it.
            // Cancel, timeout and callback failures before handoff still own it here.
            if (!handedOff) runCatching { context.unbindService(connection) }
        }'''+s[end:]
p.write_text(s)
