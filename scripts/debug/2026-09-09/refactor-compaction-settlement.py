"""HXA-176: consolidate atomic summary settlement and isolate validation/recovery decisions."""
from pathlib import Path
base=Path('app/src/main/kotlin/com/helix/app/chat')
p=base/'TurnCoordinator.kt';s=p.read_text()
a=s.index('    /** Atomically commits assistant text, Turn terminal, and the still-open ModelCall terminal. */\n    fun discardCompaction(')
b=s.index('    /** Atomically commits assistant text, Turn terminal, and the still-open ModelCall terminal. */', a+10)
s=s[:a]+s[b:]
a=s.index('    fun commitCompaction(');b=s.index('    /** Atomically commits assistant text',a)
part=s[a:b].replace('notice: String? = null,','notice: String? = null,\n        failureReason: String? = null,')
part=part.replace('require(summaryStream && stream.completed && stream.terminal(false).state == TurnState.COMPLETED)', 'require(summaryStream)\n        if (failureReason == null) require(stream.completed && stream.terminal(false).state == TurnState.COMPLETED)')
part=part.replace('            ContextCompaction.persist(storage, sessionId, turnId, idGenerator(), plan, stream.text, current.modelCallId)', '            if (failureReason == null) {\n                ContextCompaction.persist(storage, sessionId, turnId, idGenerator(), plan, stream.text, current.modelCallId)\n            }')
part=part.replace('if (notice != null)', 'if (notice != null && failureReason == null)').replace('                "COMPLETED",','                if (failureReason == null) "COMPLETED" else "FAILED",')
s=s[:a]+part+s[b:];p.write_text(s)
p=base/'ContextCompactionRound.kt';s=p.read_text()
s=s.replace('        val bypass = bypassAt?.let { bounded.inputTokens() < it + maxOf(256, it / 5) } == true\n','')
s=s.replace('if (attempts < 2 && !bypass)', 'if (shouldAttempt(bounded))')
a=s.index('        val failure =\n',s.index('    suspend fun finish'))
b=s.index('        if (failure != null)',a)
validation=s[a:b].replace('        val failure =','        return',1)
s=s[:a]+'        val failure = validationFailure(plan, stream, decision)\n'+s[b:]
a=s.index('        if (failure != null)',s.index('    suspend fun finish'));b=s.index('        currentCoroutineContext().ensureActive()\n        coordinator.commitCompaction',a)
recovery=s[a:b]
recovery=recovery.replace('        if (failure != null) {\n','',1)
recovery=recovery[:recovery.rfind('        }')]
recovery=recovery.replace('coordinator.discardCompaction(nextId, requireNotNull(failure.errorCode))','coordinator.commitCompaction(plan, nextId, failureReason = requireNotNull(failure.errorCode))')
s=s[:a]+'        if (failure != null) return recover(plan, stream, failure, coordinator, nextId)\n'+s[b:]
end=s.rfind('}')
s=s[:end]+'''
    private fun shouldAttempt(request: ChatContextRequest): Boolean {
        val bypass = bypassAt?.let { request.inputTokens() < it + maxOf(256, it / 5) } == true
        return attempts < 2 && !bypass
    }

    private fun validationFailure(
        plan: ContextCompaction.Plan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
    ): ModelStreamTerminal? {
'''+validation+'''    }

    private suspend fun recover(
        plan: ContextCompaction.Plan,
        stream: ModelStreamState,
        failure: ModelStreamTerminal,
        coordinator: TurnCoordinator,
        nextId: String,
    ): ModelStreamTerminal? {
'''+recovery+'''    }
}
'''
p.write_text(s)
