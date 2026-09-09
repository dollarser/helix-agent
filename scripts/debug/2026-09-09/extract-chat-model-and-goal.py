from pathlib import Path
root=Path('app/src/main/kotlin/com/helix/app/chat')
p=root/'ChatToolCalls.kt';s=p.read_text().replace('private val settlement = ChatToolSettlement','private val outcomeStore = ChatToolSettlement').replace('settlement.settleToolCall','outcomeStore.settleToolCall').replace('settlement.persistRejectedToolCall','outcomeStore.persistRejectedToolCall');p.write_text(s)
p=root/'ChatService.kt';s=p.read_text();imports=s[:s.index('/**')]
a=s.index('    private suspend fun runWithGoalTime(');b=s.index('    private fun applyEvent(',a)
loop=s[a:b].replace('private suspend fun runWithGoalTime','suspend fun runWithGoalTime').replace('private suspend fun runToolLoop','suspend fun runToolLoop')
s=s[:a]+s[b:]
s=s.replace('runWithGoalTime(coordinator.id)', 'modelLoop.runWithGoalTime(coordinator.id)').replace('runToolLoop(sessionId, coordinator, providerId, retryTurnId, control)','modelLoop.runToolLoop(sessionId, coordinator, providerId, retryTurnId, control)')
(root/'ChatModelLoop.kt').write_text(imports+'''/** Owns model steps, compaction admission and ordered tool rounds within one admitted Turn. */
@Suppress("LongParameterList")
internal class ChatModelLoop(
    private val storage: HelixStorage, private val providerService: ProviderService,
    private val requestAssembler: ChatRequestAssembler, private val toolCalls: ChatToolCalls,
    private val clock: Clock, private val idGenerator: () -> String,
    private val goalTimes: java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>,
    private val turnCancels: java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>,
    private val strings: (Int, Array<out Any>) -> String,
    private val refreshScreen: () -> Unit,
    private val applyEvent: (com.helix.core.model.ModelEvent, ModelStreamState, String) -> Unit,
) {
    private fun str(resId: Int, vararg args: Any): String = strings(resId, args)
'''+loop+'}\n')
a=s.index('    /** User-action service entry');b=s.index('    @Suppress("SwallowedException") // Rejected stored Goal',a)
goal=s[a:b].replace('internal suspend fun','suspend fun').replace('resolvableOpenSessionId()', 'openSessionId()')
s=s[:a]+'''    internal suspend fun createGoal(objective: String, criteria: List<String>, budgets: com.helix.core.model.GoalBudgets) =
        goals.createGoal(objective, criteria, budgets)
    internal suspend fun goalSummaries() = goals.goalSummaries()
    internal suspend fun setGoalReminder(goalId: String, delayMillis: Long?) = goals.setGoalReminder(goalId, delayMillis)
    internal suspend fun recheckGoalBlocker(goalId: String) = goals.recheckGoalBlocker(goalId)
    internal suspend fun updateGoalBudgets(goalId: String, budgets: com.helix.core.model.GoalBudgets) =
        goals.updateGoalBudgets(goalId, budgets)

'''+s[b:]
(root/'ChatGoalActions.kt').write_text(imports+'''/** Explicit Goal management; starting a Turn remains subject to the facade's send admission. */
@Suppress("LongParameterList")
internal class ChatGoalActions(
    private val storage: HelixStorage, private val clock: Clock, private val idGenerator: () -> String,
    private val requestAssembler: ChatRequestAssembler, private val runControlStore: RunControlStore,
    private val openSessionId: () -> String?, private val goalReminderSync: (String) -> Unit,
) {
'''+goal+'}\n')
pos=s.index('    private val recovery by lazy')
s=s[:pos]+'''    private val modelLoop by lazy {
        ChatModelLoop(storage, providerService, requestAssembler, toolCalls, clock, idGenerator,
            goalTimes, turnCancels, strings, ::refreshScreen, ::applyEvent)
    }
    private val goals by lazy {
        ChatGoalActions(storage, clock, idGenerator, requestAssembler, runControlStore,
            ::resolvableOpenSessionId, goalReminderSync)
    }
'''+s[pos:]
p.write_text(s)
