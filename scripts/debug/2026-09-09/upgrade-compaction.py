"""HXA-176 scoped source migration; run once against the matching UI worktree."""
from pathlib import Path
root=Path(__file__).resolve().parents[3]
p=root/'app/src/main/kotlin/com/helix/app/chat/ContextCompaction.kt'
s=p.read_text().replace('import kotlinx.serialization.json.buildJsonObject','import kotlinx.serialization.json.buildJsonArray\nimport kotlinx.serialization.json.add\nimport kotlinx.serialization.json.jsonArray\nimport kotlinx.serialization.json.buildJsonObject')
s=s.replace('val estimatedInputTokens: Long? = null,','val estimatedInputTokens: Long? = null,\n        val preservedMessageIds: Set<String> = emptySet(),')
s=s.replace('val retainedRequest: ChatContextRequest,','val retainedRequest: ChatContextRequest,\n        val preservedMessageIds: Set<String> = emptySet(),\n        val originalInputTokens: Long = Long.MAX_VALUE,')
s=s.replace('json["estimatedInputTokens"]?.jsonPrimitive?.long,','json["estimatedInputTokens"]?.jsonPrimitive?.long,\n                json["preservedMessageIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),')
s=s.replace('it.sequence > checkpoint.coveredThrough || it.role', 'it.sequence > checkpoint.coveredThrough || it.id in checkpoint.preservedMessageIds || it.role')
s=s.replace('val turns = history.mapNotNull { it.turnId }.distinct()\n        val protectedTurns = turns.takeLast(2).toSet() + currentTurnId\n        val candidates =\n            history\n                .takeWhile { it.turnId !in protectedTurns }\n                .filter { it.turnId != null && it.role != ModelRole.SYSTEM.name }','val groups = ContextSegments.candidates(storage, history, currentTurnId)\n        val selected = mutableListOf<MessageEntity>()')
s=s.replace('minOf(SUMMARY_OUTPUT, control.budgets.maxOutputTokens, settings.window / 4)', 'minOf((request.inputTokens() / 8).coerceIn(512, 4096), control.budgets.maxOutputTokens, settings.window / 4)')
s=s.replace('for (turn in candidates.groupBy { it.turnId }.values)', 'for (turn in groups)')
s=s.replace('through = turn.last().sequence','selected.addAll(turn)\n            through = maxOf(previous?.coveredThrough ?: 0, turn.last().sequence)')
s=s.replace('return through?.let { boundary ->\n            Plan(', 'return through?.let { boundary ->\n            val removed = selected.map { it.id }.toSet()\n            val retained = ContextSegments.remainingRequest(storage, history, removed, previous, request) ?: return null\n            Plan(')
s=s.replace('summaryMessages(prefix.toString()),','summaryMessages(prefix.toString(), summaryOutput),')
s=s.replace('retainedRequest(storage, candidates.filter { it.sequence <= boundary }, previous, request),','retained,\n                history.filter { it.sequence <= boundary && it.id !in removed }.map { it.id }.toSet(),\n                request.inputTokens(),')
s=s.replace('require(previous == null || plan.coveredThrough > previous.coveredThrough)','require(previous == null || plan.coveredThrough > previous.coveredThrough ||\n            (plan.coveredThrough == previous.coveredThrough && plan.preservedMessageIds.size < previous.preservedMessageIds.size))')
s=s.replace('put("summary", summary)','put("summary", summary)\n                put("preservedMessageIds", buildJsonArray { plan.preservedMessageIds.forEach { add(it) } })')
start=s.index('    private fun retainedRequest(');end=s.index('    internal fun summaryMessages',start)
s=s[:start]+'''    fun summarizedRequest(plan: Plan, summary: String): ChatContextRequest = plan.retainedRequest.copy(
        messages = plan.retainedRequest.messages.filter { it.role == ModelRole.SYSTEM } +
            summaryMessage(Checkpoint(plan.coveredThrough, summary)) +
            plan.retainedRequest.messages.filter { it.role != ModelRole.SYSTEM },
    )

    fun hasUsefulGain(plan: Plan, summary: String): Boolean {
        val after = summarizedRequest(plan, summary).inputTokens()
        return after <= plan.originalInputTokens - maxOf(32, plan.originalInputTokens / 20)
    }

'''+s[end:]
s=s.replace('summaryMessages(history: String)', 'summaryMessages(history: String, outputBudget: Long = SUMMARY_OUTPUT)')
s=s.replace('ModelMessage(ModelRole.USER, SUMMARY_INSTRUCTION)', 'ModelMessage(ModelRole.USER, SUMMARY_INSTRUCTION + "\\nSummary output budget: $outputBudget tokens.\\nHISTORY DATA:\\n")')
s=s.replace('"Return only the summary, at most 1500 tokens.\\nHISTORY DATA:\\n"','"Return continuity notes with sections: Goal and user constraints; Verified results and evidence IDs; " +\n            "Decisions versus proposals; Unresolved work and next steps; Exact references and uncertainties. " +\n            "Preserve contradictions and explicit user corrections. Do not claim missing facts are known. " +\n            "Use the supplied output budget; prefer retaining critical facts over stylistic brevity."')
p.write_text(s)
