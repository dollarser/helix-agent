from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesScreen.kt');s=p.read_text().replace('import android.net.Uri','''import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier''');s=s.replace('            FilesScreenLayout(state, actions, openSharedStorage)','''            Column(Modifier.fillMaxSize()) {
                FilesRecoveryPanel(actions)
                Box(Modifier.weight(1f)) { FilesScreenLayout(state, actions, openSharedStorage) }
            }''');p.write_text(s)
zh={'title':'传输恢复','explanation':'恢复会清理未完成的暂存，或核实已发布的目标。移动操作不会再次自动删除源文件；完成后请检查源目录。','empty':'没有待恢复的传输','action':'恢复文件操作','published':'目标已核实，恢复完成。请检查并自行整理源目录中的剩余文件。','retry':'已恢复到可重试状态，请重新发起复制或移动。'}
en={'title':'Transfer recovery','explanation':'Recovery removes unfinished staging or verifies a published destination. It never replays source deletion for a move. Review the source afterward.','empty':'No transfers need recovery','action':'Recover file operation','published':'Destination verified and recovery completed. Review any remaining source files.','retry':'Restored to a retryable state. Start the copy or move again.'}
for folder,values in [('values',zh),('values-zh-rCN',zh),('values-en',en)]:
 p=Path('app/src/main/res')/folder/'strings.xml';s=p.read_text();s=s.replace('</resources>',''.join(f'    <string name="files_recovery_{k}">{v}</string>\n' for k,v in values.items())+'</resources>');p.write_text(s)
