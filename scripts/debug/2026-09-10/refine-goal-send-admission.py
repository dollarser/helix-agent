from pathlib import Path
base=Path('app/src/main/kotlin/com/helix/app')
p=base/'chat/GoalComposerStart.kt';s=p.read_text().replace('import com.helix.core.model.Clock','import com.helix.core.model.AgentMode\nimport com.helix.core.model.Clock');i=s.index('/** Called under');s=s[:i]+'''internal data class GoalSendStart(val required: Boolean, val started: StartedGoalTurn?)

'''+s[i:];i=s.index('    fun start(');s=s[:i]+'''    fun admit(goalId: String?, spec: TurnStartSpec, control: RunControlConfig, retry: Boolean): GoalSendStart {
        if (goalId != null) {
            val started = GoalRunCoordinator(storage, clock, idGenerator)
                .start(GoalTurnStart(goalId, GoalWakeReason.USER_OPEN, spec, control.budgets))
            return GoalSendStart(true, started)
        }
        val prompt = spec.userText
        val required = control.mode == AgentMode.GOAL && !retry && prompt != ContextCompaction.COMMAND
        val started = if (required && !prompt.isNullOrBlank()) start(prompt, spec, control) else null
        return GoalSendStart(required, started)
    }

'''+s[i:];p.write_text(s)
p=base/'chat/ChatService.kt';s=p.read_text();a=s.index('            val goalStart =\n',s.index('private suspend fun launchTurn'));b=s.index('                setBlocked(str(R.string.goal_continue_unavailable))',a);s=s[:a]+'''            val goalSend = GoalComposerStart(storage, clock, idGenerator).admit(goalId, spec, control, retryTurnId != null)
            val goalStart = goalSend.started
            if (goalSend.required && goalStart == null) {
'''+s[b:];s=s.replace('''        if (runControlStore.current.mode == AgentMode.GOAL && text.isBlank()) {
            setBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
            return
        }
''','');s=s.replace('if (text.isBlank() && staged.isEmpty()) {','if (text.isBlank() && (staged.isEmpty() || runControlStore.current.mode == AgentMode.GOAL)) {');p.write_text(s)
p=base/'ui/GoalDialog.kt';s=p.read_text().replace('    busy: Boolean = false,\n','');s=s.replace('import com.helix.app.R','import androidx.lifecycle.compose.collectAsStateWithLifecycle\nimport com.helix.app.R');s=s.replace('    var rows by remember', '    val screen by service.screen.collectAsStateWithLifecycle()\n    val busy = screen.isSending\n    var rows by remember',1);p.write_text(s)
p=base/'ui/ChatScreen.kt';s=p.read_text().replace('            busy = screen.isSending,\n','');p.write_text(s)
p=Path('scripts/debug/2026-09-10/verify-goal-entry.py');s=p.read_text().replace(" os.environ['JAVA_HOME']=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()", " located=subprocess.run(['/usr/libexec/java_home','-v','17'],text=True,capture_output=True)\n os.environ['JAVA_HOME']=located.stdout.strip() if located.returncode==0 else str(Path(subprocess.check_output(['brew','--prefix','openjdk@17'],text=True).strip())/'libexec/openjdk.jdk/Contents/Home')");p.write_text(s)
