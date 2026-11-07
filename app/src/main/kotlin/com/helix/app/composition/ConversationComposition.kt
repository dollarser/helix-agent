package com.helix.app.composition

import com.helix.app.agent.AgentLoopHost
import com.helix.app.agent.TurnInputDelivery
import com.helix.app.agent.TurnSteeringDraft
import com.helix.app.application.agent.AppAgentRuntime
import com.helix.app.application.agent.GoalContinuationDriver
import com.helix.app.application.agent.GoalUserRequest
import com.helix.app.application.agent.TurnSubmissionCommandHandler
import com.helix.app.application.tools.ApplicationToolGateway
import com.helix.app.chat.AttachmentStagingSupport
import com.helix.app.chat.ChatRequestAssembler
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatToolMessages
import com.helix.app.chat.ConversationLaunchStore
import com.helix.app.infrastructure.agent.ProviderSubmissionSnapshots
import com.helix.app.infrastructure.agent.RoomSubmissionAdmissionFacts
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import com.helix.app.knowledge.KnowledgeNoteService
import com.helix.app.knowledge.KnowledgeReferenceService
import com.helix.app.memory.MemoryService
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.app.provider.SubscriptionRecoveredOutput
import com.helix.app.provider.SubscriptionRecoveryStatus
import com.helix.app.runcontrol.PersistedRunControlStore
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.app.scheduled.ScheduledModelRequestGuard
import com.helix.app.tool.ToolPipeline
import com.helix.app.vision.MobileUseScreenConsent
import com.helix.app.vision.ToolVisionConsent
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.runtime.StopTurnCommandHandler
import com.helix.core.agent.runtime.TurnDeliveryControl
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import com.helix.core.policy.AutomationSessionScope
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap

/**
 * Application composition, shared by production and fixture hosts. The supplied Engine owns all
 * live/durable execution; callbacks below keep settlement synchronous even without a visible page.
 * Construction only wires the graph. [start] starts the conversation's disposable observation.
 */
@Suppress("LongParameterList") // One explicit application graph, not a second execution owner.
internal class ConversationComposition(
    storage: HelixStorage,
    providerService: ProviderService,
    profileStore: SafetyProfileStore,
    runControlStore: RunControlStore =
        PersistedRunControlStore(InMemoryLineStore()).also { it.setMode(AgentMode.ACT) },
    conversationLaunchStore: ConversationLaunchStore = ConversationLaunchStore(InMemoryLineStore()),
    toolPipeline: ToolPipeline,
    clock: Clock = SystemClock(),
    idGenerator: () -> String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    attachmentStaging: AttachmentStagingSupport,
    visionSessionBinder: (String) -> Unit = {},
    strings: (Int, Array<out Any>) -> String = { resId, _ -> "§$resId" },
    turnEngine: TurnEngine = createRoomTurnEngine(storage, clock, idGenerator),
    lanScopes: () -> Set<NetworkOriginScope> = { emptySet() },
    subscriptionResultRecovery: (String, String, Boolean) -> SubscriptionRecoveredOutput? = { _, _, _ -> null },
    subscriptionRecovery: (String, String, Boolean) -> SubscriptionRecoveryStatus =
        { _, _, _ -> SubscriptionRecoveryStatus.UNKNOWN },
    goalReminderSync: (String) -> Unit = {},
    bindSessionDirectory: (String) -> String = { it },
    projectInstructionsReader: (FileScopePath) -> String = { "" },
    memory: MemoryService? = null,
    mobileScreenScope: (String) -> AutomationSessionScope? = { null },
    mobileScreenSources: LineStore = InMemoryLineStore(),
    knowledgeReferences: KnowledgeReferenceService? = null,
    knowledgeNotes: KnowledgeNoteService? = null,
    scheduledModelGuard: ScheduledModelRequestGuard = ScheduledModelRequestGuard(),
) {
    private val sessionRunControls = SessionRunControlStore(storage, runControlStore)
    private val goalContinuation = GoalContinuationDriver(storage)
    private val goalUserRequests = ConcurrentHashMap<String, List<GoalUserRequest>>()
    private val toolVisionConsent =
        ToolVisionConsent(
            storage.interactionReceipts,
            clock,
            MobileUseScreenConsent(clock, mobileScreenSources, mobileScreenScope),
        )
    private val requestAssembler =
        ChatRequestAssembler(
            storage,
            providerService,
            toolPipeline,
            attachmentStaging,
            visionSessionBinder,
            projectInstructionsReader,
            memory,
            toolVisionConsent,
            profile = { profileStore.flow.value },
        )
    private val toolCalls =
        ApplicationToolGateway(
            storage,
            toolPipeline,
            clock,
            idGenerator,
            profileStore.flow,
            scope,
            presentation = { fact -> chatService.toolPresentation.publish(fact) },
            turnEngine.liveHandles,
            lanScopes,
            attachmentStaging.workspaceScopeId,
        )
    private val agentLoop =
        AgentLoopHost(
            storage,
            providerService,
            requestAssembler,
            toolCalls,
            ChatToolMessages(storage, toolPipeline, strings),
            clock,
            idGenerator,
            turnEngine.liveHandles,
            strings,
            refreshScreen = { chatService.refreshScreen() },
            onTextChanged = { turn, text -> chatService.publishModelText(turn, text) },
            beforeModelStream = scheduledModelGuard::verify,
            inputDelivery =
                object : TurnInputDelivery {
                    override suspend fun prepareSteering(
                        sessionId: String,
                        turnId: String,
                    ): TurnSteeringDraft? = chatService.sessionInputDelivery.prepareSteering(sessionId, turnId)

                    override fun requestStarting(
                        sessionId: String,
                        turnId: String,
                        modelCallId: String,
                        messageIds: Set<String>,
                    ) {
                        chatService.bindInputAuthority(sessionId, turnId, modelCallId, messageIds)
                    }
                },
        )
    private val submissionCommands =
        TurnSubmissionCommandHandler(
            admissionFacts = RoomSubmissionAdmissionFacts(storage),
            clock = clock,
            idGenerator = idGenerator,
            providerSnapshots = ProviderSubmissionSnapshots(providerService),
            sessionRunControls = sessionRunControls,
            turnEngine = turnEngine,
            goalContinuation = goalContinuation,
            goalUserRequests = goalUserRequests,
            workScope = scope,
            agentLoop = agentLoop,
            executionHooks = { session -> chatService.executionHooks(session) },
            inspectOriginal = { turn -> chatService.recovery.inspectOriginal(turn) },
            promptTemplates = packagedPromptTemplates,
        )
    private val stopCommands =
        StopTurnCommandHandler(
            turnEngine,
            delivery =
                object : TurnDeliveryControl {
                    override fun handoffOwner(sessionId: String) = goalContinuation.handoffOwner(sessionId)

                    override fun disarmTurn(
                        sessionId: String,
                        turnId: String,
                    ) = goalContinuation.disarmTurn(sessionId, turnId)

                    override fun disarm(sessionId: String) = goalContinuation.disarm(sessionId)

                    override fun forgetUserRequests(turnId: String) {
                        goalUserRequests.remove(turnId)
                    }
                },
            pendingApprovals = toolCalls::cancelPendingApproval,
        )
    val agentRuntime: AgentRuntime = AppAgentRuntime(submissionCommands, stopCommands, turnEngine.runtimeView)
    val chatService: ChatService =
        ChatService(
            storage = storage,
            providerService = providerService,
            profileStore = profileStore,
            conversationLaunchStore = conversationLaunchStore,
            toolPipeline = toolPipeline,
            clock = clock,
            idGenerator = idGenerator,
            scope = scope,
            attachmentStaging = attachmentStaging,
            strings = strings,
            turnEngine = turnEngine,
            sessionRunControls = sessionRunControls,
            goalContinuation = goalContinuation,
            goalUserRequests = goalUserRequests,
            requestAssembler = requestAssembler,
            toolCalls = toolCalls,
            submissionCommands = submissionCommands,
            stopCommands = stopCommands,
            agentRuntime = agentRuntime,
            toolVisionConsent = toolVisionConsent,
            subscriptionResultRecovery = subscriptionResultRecovery,
            subscriptionRecovery = subscriptionRecovery,
            goalReminderSync = goalReminderSync,
            bindSessionDirectory = bindSessionDirectory,
            knowledgeReferences = knowledgeReferences,
            knowledgeNotes = knowledgeNotes,
        )

    fun start() = chatService.start()
}
