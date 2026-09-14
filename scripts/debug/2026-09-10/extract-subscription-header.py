"""One-time extraction of presentation header from screen assembly."""
from pathlib import Path
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionScreen.kt');s=p.read_text()
a=s.index('        val header =');b=s.index('        content.setOnApplyWindowInsetsListener',a)
body=s[a:b].replace('        shell.addView(header)\n','        return header\n')
s=s[:a]+'        shell.addView(header(activity, home, ::dp))\n'+s[b:]
pos=s.index('    private fun styleContent(')
s=s[:pos]+'    private fun header(activity: Activity, home: Boolean, dp: (Int) -> Int): LinearLayout {\n'+body+'    }\n\n'+s[pos:]
p.write_text(s)
