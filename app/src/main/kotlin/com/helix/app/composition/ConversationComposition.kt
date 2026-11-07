package com.helix.app.composition

import android.util.Log
import com.helix.app.agent.AgentLoopHost
import com.helix.app.agent.GoalDetachedBudget
import com.helix.app.agent.GoalLeaseControl
import com.helix.app.agent.TurnInputDelivery
import com.helix.app.application.agent.AppAgentRuntime
import com.helix.app.application.agent.GoalContinuationDriver
import com.helix.app.application.agent.GoalUserRequest
import com.helix.app.application.agent.TurnSubmissionCommandHandler
import com.helix.app.application.tools.AppConversationTools
import com.helix.app.application.tools.ApplicationToolGateway
import com.helix.app.application.tools.ToolExecution
import com.helix.app.chat.ChatRequestAssembler
import com.helix.app.chat.ChatToolMessages
import com.helix.app.chat.HumanHandoff
import com.helix.app.chat.TaskDashboardProjection
import com.helix.app.chat.loadTurnRecoverySources
import com.helix.app.infrastructure.agent.ProviderSubmissionSnapshots
import com.helix.app.infrastructure.agent.RoomSessionWorkStore
import com.helix.app.infrastructure.agent.RoomSubmissionAdmissionFacts
import com.helix.app.infrastructure.agent.RoomTurnRecoveryStore
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.app.infrastructure.chat.AppInputDeliveryProvider
import com.helix.app.infrastructure.chat.AppInputDeliveryRuntime
import com.helix.app.infrastructure.chat.AttachmentStagingSupport
import com.helix.app.infrastructure.chat.ConversationReferenceResolver
import com.helix.app.infrastructure.chat.ProotRecoveryAdapter
import com.helix.app.infrastructure.chat.RoomRecoveryObservationStore
import com.helix.app.infrastructure.chat.RoomSessionInputCommands
import com.helix.app.infrastructure.chat.RoomSessionInputDeliveryStore
import com.helix.app.infrastructure.chat.SessionInputAttachments
import com.helix.app.infrastructure.chat.StorageAttachmentAdapter
import com.helix.app.infrastructure.conversation.AppConversationGoalRuntime
import com.helix.app.infrastructure.conversation.AppConversationProviders
import com.helix.app.infrastructure.conversation.ConversationExperts
import com.helix.app.infrastructure.conversation.ConversationWorkspaceDetails
import com.helix.app.infrastructure.conversation.LineConversationLaunchStore
import com.helix.app.infrastructure.conversation.RoomConversationGoals
import com.helix.app.infrastructure.conversation.RoomConversationHistoryReader
import com.helix.app.infrastructure.conversation.RoomConversationKnowledge
import com.helix.app.infrastructure.conversation.RoomConversationScreenReader
import com.helix.app.infrastructure.conversation.RoomConversationSubmissionHistory
import com.helix.app.infrastructure.conversation.RoomConversationTurnHistory
import com.helix.app.infrastructure.conversation.RoomConversationWorkspace
import com.helix.app.infrastructure.conversation.RoomSessionCatalog
import com.helix.app.infrastructure.conversation.StoredConversationInputCache
import com.helix.app.infrastructure.conversation.UserMessageEditor
import com.helix.app.infrastructure.conversation.roomSessionDirectory
import com.helix.app.infrastructure.conversation.toConversationDisplay
import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import com.helix.app.knowledge.KnowledgeNoteService
import com.helix.app.knowledge.KnowledgeReferenceService
import com.helix.app.memory.MemoryService
import com.helix.app.plan.PlanReviewService
import com.helix.app.plan.StoragePlanReviewPort
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTurnSnapshot
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
import com.helix.core.agent.turn.TurnSteeringInput
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import com.helix.core.policy.AutomationSessionScope
import com.helix.core.policy.ForbiddenContentGuard
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.RoomInputRecoveryStore
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.application.ChatService
import com.helix.feature.conversation.application.ConversationLaunchStore
import com.helix.feature.conversation.application.input.SessionInputDeliveryCoordinator
import com.helix.feature.conversation.presentation.ChatStatusLabels
import com.helix.feature.conversation.presentation.conversationScreenProjection
import com.helix.provider.api.SubscriptionRecoveredOutput
import com.helix.provider.api.SubscriptionRecoveryStatus
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
    conversationLaunchStore: ConversationLaunchStore = LineConversationLaunchStore(InMemoryLineStore()),
    toolPipeline: ToolPipeline,
    clock: Clock = SystemClock(),
    idGenerator: () -> String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    attachmentStaging: AttachmentStagingSupport,
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
    val toolVisionConsent =
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
            presentation = { fact -> chatService.toolPresentation.publish(fact.toConversationDisplay()) },
            turnEngine.liveHandles,
            lanScopes,
            attachmentStaging.workspaceScopeId,
        )
    val toolExecution: ToolExecution get() = toolCalls

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
                    ): TurnSteeringInput? = chatService.sessionInputDelivery.prepareSteering(sessionId, turnId)

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
    val goalRuntime =
        AppConversationGoalRuntime(
            storage,
            clock,
            idGenerator,
            turnEngine,
            goalContinuation,
            goalUserRequests,
        )
    private val goals =
        RoomConversationGoals(
            storage,
            clock,
            idGenerator,
            contextFits = { session, objective ->
                requestAssembler.contextFits(
                    session,
                    sessionRunControls.ensure(session, clock.now().toEpochMilli()),
                    objective,
                )
            },
            turnGate = turnEngine.executionGate,
            goalReminderSync = goalReminderSync,
        )
    private val plans = PlanReviewService(StoragePlanReviewPort(storage, clock, idGenerator))
    private val detachedGoalBudget =
        GoalDetachedBudget(
            storage,
            clock,
            { turn -> turnEngine.liveHandles.goalTime(turn) as? GoalLeaseControl },
            idGenerator,
        )
    val agentRuntime: AgentRuntime = AppAgentRuntime(submissionCommands, stopCommands, turnEngine.runtimeView)
    private val workspaceRecovery = RoomConversationWorkspace(storage, bindSessionDirectory)
    private val recoveryObservationStore = RoomRecoveryObservationStore(storage)
    private val historyReader =
        RoomConversationHistoryReader(
            storage,
            providerService,
            strings,
            ChatStatusLabels(strings)::modelTerminalCodeRes,
        )
    val taskDashboard =
        TaskDashboardProjection(storage, scope, turnEngine.executionGate, clock) { goalRuntime.hasHandoff }
    val conversationDetails =
        ConversationWorkspaceDetails(storage, historyReader, conversationScreenProjection(strings)) {
            chatService.screen.value.openSessionId
        }
    val conversationExperts = ConversationExperts(storage, clock, scope) { chatService.screen.value.openSessionId }
    private val inputDeliveryStore = RoomSessionInputDeliveryStore(storage)
    private val sessionInputDelivery =
        SessionInputDeliveryCoordinator(
            store = inputDeliveryStore,
            provider =
                AppInputDeliveryProvider(providerService) { provider, model ->
                    ProviderTurnSnapshot.capture(providerService, provider, model)
                },
            runtime =
                AppInputDeliveryRuntime(turnEngine, sessionRunControls, submissionCommands, clock) { session, result ->
                    chatService.projectSubmission(session, result)
                },
            materialize = { input ->
                inputDeliveryStore.materialize(input, attachmentStaging, ForbiddenContentGuard::reasonFor)
            },
            clock = clock,
            turnGate = turnEngine.executionGate,
            reportFailure = { Log.w("SessionInputDelivery", "Queued input revalidation failed", it) },
            knowledgeReferencesValid = { items -> items.isEmpty() || knowledgeReferences?.valid(items) == true },
            referenceToolsAvailable = { session, conversation, knowledge ->
                chatService.referenceToolsAvailable(session, conversation, knowledge)
            },
            skillActivationsValid = { session, items -> chatService.validSkillActivations(session, items) },
        )
    val chatService: ChatService =
        ChatService(
            providers = AppConversationProviders(providerService),
            profile = profileStore.flow,
            catalog =
                RoomSessionCatalog(
                    storage,
                    providerService,
                    sessionRunControls,
                    workspaceRecovery,
                    clock,
                    idGenerator,
                    bindSessionDirectory,
                ),
            historyReader = historyReader,
            screenReader =
                RoomConversationScreenReader(
                    storage,
                    historyReader,
                    providerService,
                    sessionRunControls::defaultSnapshot,
                    workspaceRecovery,
                    recoveryObservationStore,
                ),
            directoryService = roomSessionDirectory(scope, storage, providerService),
            recoveryObservationStore = recoveryObservationStore,
            prootRecovery = ProotRecoveryAdapter(storage),
            inputRecoveryStore = RoomInputRecoveryStore(storage, turnEngine.executionGate),
            turnRecoveryStore =
                RoomTurnRecoveryStore(
                    storage,
                    turnEngine.executionGate,
                    turnEngine::finishAutomaticRecovery,
                ),
            sessionWorkStore = RoomSessionWorkStore(storage),
            recoverySources = { session, turn -> loadTurnRecoverySources(storage, session, includeTurnId = turn) },
            sessionInputDelivery = sessionInputDelivery,
            inputCommands = RoomSessionInputCommands(storage),
            inputCache = StoredConversationInputCache(storage.composerDrafts),
            submissionHistory = RoomConversationSubmissionHistory(storage),
            messageEditor = UserMessageEditor(storage),
            turnHistory = RoomConversationTurnHistory(storage),
            acceptedInputDisclosure =
                SessionInputAttachments(
                    storage,
                    attachmentStaging,
                    ForbiddenContentGuard::reasonFor,
                ),
            conversationLaunchStore = conversationLaunchStore,
            clock = clock,
            idGenerator = idGenerator,
            scope = scope,
            attachmentPort = StorageAttachmentAdapter(storage, attachmentStaging, idGenerator, strings),
            strings = strings,
            turnEngine = turnEngine,
            sessionRunControls = sessionRunControls,
            goals = goals,
            goalRuntime = goalRuntime,
            dashboards = taskDashboard,
            humanHandoff = HumanHandoff(storage) { clock.now().toEpochMilli() },
            conversationReferences = ConversationReferenceResolver(storage),
            knowledge = RoomConversationKnowledge(storage, knowledgeReferences, knowledgeNotes, strings),
            planReview = plans,
            detachedGoalBudget = detachedGoalBudget,
            toolCalls = AppConversationTools(toolPipeline, toolCalls),
            submissionCommands = submissionCommands,
            stopCommands = stopCommands,
            agentRuntime = agentRuntime,
            subscriptionResultRecovery = subscriptionResultRecovery,
            subscriptionRecovery = subscriptionRecovery,
            bindSessionDirectory = bindSessionDirectory,
        )

    fun start() = chatService.start()
}
