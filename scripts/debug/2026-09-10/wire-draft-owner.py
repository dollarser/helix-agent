#!/usr/bin/env python3
"""HXA-183 one-shot draft owner wiring."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
p=ROOT/'app/src/main/kotlin/com/helix/app/chat/ChatService.kt'; s=p.read_text()
def replace(a,b):
    global s
    assert a in s,a[:100]
    s=s.replace(a,b)
replace('    private val draftLock = Any()\n\n    @Volatile private var sessionDraft: SessionDraft? = null\n\n    @Volatile private var preparingDraft = false', '''    private val drafts = ChatDraftStore()
    private val sessionDraft: SessionDraft? get() = drafts.current
    private val preparingDraft: Boolean get() = drafts.preparing''')
replace('        synchronized(draftLock) { sessionDraft = SessionDraft(entity) }','        if (!drafts.open(entity)) return')
start=s.index('    private fun saveSessionDraft('); end=s.index('    fun renameSession(',start)
s=s[:start]+'''    private fun saveSessionDraft(text: String): List<DraftAttachment>? {
        val attachments = drafts.persist(openSessionId, text, str(R.string.chat_attachment_button)) { row ->
            storage.withTransaction {
                storage.sessions.create(row.id, row.title, row.providerId, row.modelId, row.createdAt)
                storage.sessions.updateDetails(row.id, row.title, row.directoryRef)
            }
        } ?: return null
        refreshSessionsNow()
        refreshScreen()
        return attachments
    }

'''+s[end:]
start=s.index('                synchronized(draftLock)'); end=s.index('                refreshScreen()',start)
s=s[:start]+'                drafts.rename(id, title)\n'+s[end:]
replace('sessionDraft = draft.copy(session = draft.session.copy(directoryRef = reference))','drafts.directory(draft.session.id, reference)')
replace('        sessionDraft = null','        drafts.clear()')
replace('sessionDraft = it.copy(session = it.session.copy(providerId = providerId, modelId = modelId))','drafts.model(it.session.id, providerId, modelId)')
replace('sessionDraft = draft.copy(session = draft.session.copy(providerId = providerId, modelId = modelId))','drafts.model(draft.session.id, providerId, modelId)')
start=s.index('            synchronized(draftLock)'); end=s.index('            refreshScreen()',start)
s=s[:start]+'''            drafts.addAttachment(sessionId, DraftAttachment(
                idGenerator(), uri, reported.displayName.orEmpty(), reported.sizeBytes,
            ))
'''+s[end:]
start=s.index('            synchronized(draftLock)'); end=s.index('            synchronized(stagedLock)',start)
s=s[:start]+'            drafts.removeAttachment(id)\n'+s[end:]
replace('        preparingDraft = true','        if (!drafts.beginPreparation(draft.session.id)) return')
replace('                preparingDraft = false','                drafts.finishPreparation()')
assert 'draftLock' not in s
p.write_text(s)
