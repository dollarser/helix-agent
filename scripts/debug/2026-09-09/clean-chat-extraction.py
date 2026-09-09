from pathlib import Path
r=Path('app/src/main/kotlin/com/helix/app/chat')
p=r/'ChatToolCalls.kt';s=p.read_text().replace('private val settlement =','private val outcomeStore =');p.write_text(s)
p=r/'ChatModelLoop.kt';s=p.read_text();a=s.index('    /**\n     * One stream event');s=s[:a]+'}\n';p.write_text(s)
p=r/'ChatService.kt';s=p.read_text();a=s.index('    /**\n     * The assistant tool-step') if '    /**\n     * The assistant tool-step' in s else -1
if a>=0:
 b=s.index('    // --------------------------------------------------------------------------------\n    // Screen state',a);s=s[:a]+s[b:]
p.write_text(s)
