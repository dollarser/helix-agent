# Helix Agent Mermaid Architecture Diagrams

> 适合直接放入 GitHub Markdown / Mermaid Live Editor / Obsidian / Typora 等支持 Mermaid 的工具中编辑。

---

# 1. 总体架构图

```mermaid
flowchart TB

    U["用户 / 入口<br/>Chat · Share · Voice · Widget · Assistant"]

    subgraph APP["Application Layer"]
        direction LR
        CHAT["Chat"]
        TASKS["Tasks"]
        WS["Workspace"]
        CAP["Capability Center"]
        SET["Settings"]
    end

    subgraph CORE["Agent Core"]
        direction LR
        RT["AgentRuntime"]
        LOOP["AgentLoop"]
        GOAL["Goal Driver"]
        PLAN["Plan Engine"]
        ORCH["Task Orchestrator"]
        POLICY["Safety & Policy"]
    end

    subgraph INTEL["Model & Context"]
        direction LR
        PROMPT["Prompt Registry"]
        CTX["Context Engine"]
        COMPACT["Context Compaction"]
        MODEL["Model Router"]
    end

    subgraph TOOLS["Tools & Runtime"]
        direction LR
        REG["Tool Registry"]
        APPROVAL["Permission & Approval"]
        DISPATCH["Tool Dispatcher"]
        EXEC["Execution Targets"]
        VERIFY["Result Verification"]
    end

    subgraph PLATFORM["Platform & Persistence"]
        direction LR
        DB["Room DB"]
        STORE["Workspace Store"]
        KEY["Keystore"]
        ANDROID["Android OS"]
        FGS["Foreground Service"]
        REC["Recovery"]
    end

    subgraph EXT["External Ecosystem"]
        direction LR
        LLM["LLM Providers"]
        MCP["MCP Servers"]
        A2A["A2A Agents"]
        WEB["Web / APIs"]
    end

    U --> APP
    APP --> RT
    RT --> LOOP

    GOAL --> LOOP
    PLAN --> LOOP
    ORCH --> LOOP
    POLICY --> LOOP

    LOOP --> PROMPT
    LOOP --> CTX
    PROMPT --> MODEL
    CTX --> MODEL
    COMPACT --> CTX
    MODEL --> LOOP

    LOOP --> REG
    REG --> APPROVAL
    APPROVAL --> DISPATCH
    DISPATCH --> EXEC
    EXEC --> VERIFY
    VERIFY --> LOOP

    CORE --> DB
    CORE --> REC
    EXEC --> STORE
    EXEC --> ANDROID
    CORE --> KEY
    GOAL --> FGS

    MODEL <--> LLM
    DISPATCH <--> MCP
    DISPATCH <--> A2A
    DISPATCH <--> WEB
```


```mermaid
flowchart TB

%% =========================================================
%% Helix Agent — High-Level Architecture
%% =========================================================

classDef entry fill:#EAF4FF,stroke:#4A90E2,color:#12344D,stroke-width:1px
classDef app fill:#ECFDF3,stroke:#38A169,color:#173B2A,stroke-width:1px
classDef core fill:#F3EEFF,stroke:#805AD5,color:#2D1B69,stroke-width:1px
classDef intel fill:#FFF8E6,stroke:#D69E2E,color:#5F4300,stroke-width:1px
classDef tool fill:#EDF8FF,stroke:#3182CE,color:#163A5F,stroke-width:1px
classDef data fill:#F7FAFC,stroke:#718096,color:#263238,stroke-width:1px
classDef ext fill:#FFF0F6,stroke:#D53F8C,color:#5A183B,stroke-width:1px


%% =========================================================
%% 1. USER & ENTRY
%% =========================================================

USER["👤 User<br/>Developer · Power User · Productivity User"]:::entry

subgraph ENTRY["1. User & Entry"]
direction LR
CHAT["Chat"]:::entry
TASKS["Tasks"]:::entry
WORKSPACE["Workspace"]:::entry
SHARE["Share / Voice"]:::entry
ASSIST["Widget / Assistant"]:::entry
end

USER --> ENTRY


%% =========================================================
%% 2. APPLICATION
%% =========================================================

subgraph APP["2. Application Layer"]
direction LR
CHATAPP["Chat App"]:::app
TASKAPP["Task Manager"]:::app
WSAPP["Workspace App"]:::app
CAPAPP["Capability Center"]:::app
ARTIFACT["Artifact Center"]:::app
end

CHAT --> CHATAPP
TASKS --> TASKAPP
WORKSPACE --> WSAPP
SHARE --> CHATAPP
ASSIST --> TASKAPP


%% =========================================================
%% 3. AGENT CORE
%% =========================================================

subgraph CORE["3. Agent Core"]
direction TB

RUNTIME["AgentRuntime<br/>Unified Entry"]:::core

subgraph CORECTRL["Execution Control"]
direction LR
TURN["Turn Coordinator"]:::core
GOAL["Goal Driver"]:::core
PLAN["Plan Engine"]:::core
ORCH["Task Orchestrator"]:::core
POLICY["Safety & Policy"]:::core
end

LOOP["Agent Loop<br/><b>Model → Tool → Model</b>"]:::core

RUNTIME --> TURN
GOAL --> TURN
PLAN --> LOOP
ORCH --> LOOP
POLICY --> LOOP
TURN --> LOOP

end

CHATAPP --> RUNTIME
TASKAPP --> RUNTIME
WSAPP --> RUNTIME
CAPAPP --> RUNTIME


%% =========================================================
%% 4. MODEL & CONTEXT
%% =========================================================

subgraph INTEL["4. Model & Context"]
direction LR
PROMPT["Prompt Registry"]:::intel
CONTEXT["Context Engine"]:::intel
COMPACT["Context Compaction"]:::intel
MODEL["Model Router"]:::intel
end

LOOP --> PROMPT
LOOP --> CONTEXT
COMPACT --> CONTEXT
PROMPT --> MODEL
CONTEXT --> MODEL
MODEL --> LOOP


%% =========================================================
%% 5. TOOL & EXECUTION
%% =========================================================

subgraph TOOLING["5. Tool & Execution"]
direction TB

PIPELINE["Tool Runtime<br/>Registry → Capability → Policy → Approval → Dispatch → Verify"]:::tool

subgraph CAP["Capabilities"]
direction LR
FILES["Files"]:::tool
BROWSER["Browser"]:::tool
ANDROID["Android"]:::tool
DEV["Developer"]:::tool
SKILLS["Skills / MCP / A2A"]:::tool
end

subgraph EXEC["Execution Targets"]
direction LR
E0["E0 Native"]:::tool
E1["E1 QuickJS"]:::tool
E2["E2 PRoot / Linux"]:::tool
end

PIPELINE --> FILES
PIPELINE --> BROWSER
PIPELINE --> ANDROID
PIPELINE --> DEV
PIPELINE --> SKILLS

FILES --> E0
BROWSER --> E0
ANDROID --> E0
DEV --> E2
DEV --> E1

end

LOOP --> PIPELINE


%% =========================================================
%% DATA & PLATFORM
%% =========================================================

subgraph FOUNDATION["Foundation"]
direction LR
DB["State Store<br/>Session · Turn · Goal · Approval"]:::data
STORE["Workspace Store<br/>Files · Artifacts"]:::data
KEY["Keystore"]:::data
RECOVERY["Recovery & Audit"]:::data
ANDROIDOS["Android Platform<br/>OS · WebView · Accessibility · FGS"]:::data
end

CORE --> DB
TOOLING --> STORE
CORE --> RECOVERY
TOOLING --> ANDROIDOS
MODEL --> KEY


%% =========================================================
%% EXTERNAL ECOSYSTEM
%% =========================================================

subgraph EXTERNAL["External Ecosystem"]
direction LR
LLM["LLM Providers"]:::ext
MCP["MCP Servers"]:::ext
A2A["A2A Agents"]:::ext
WEB["Web / APIs"]:::ext
end

MODEL <--> LLM
SKILLS <--> MCP
SKILLS <--> A2A
BROWSER <--> WEB


%% =========================================================
%% RESULT FLOW
%% =========================================================

LOOP --> ARTIFACT
ARTIFACT --> TASKAPP
ARTIFACT --> WSAPP
```


---

# 2. Agent Harness 核心架构

```mermaid
flowchart TB

    ENTRY["AgentRuntime<br/>统一执行入口"]

    subgraph CONTROL["控制层"]
        direction LR
        TURN["Turn Coordinator<br/>submit · resume · cancel"]
        MODE["Mode Strategy<br/>Chat · Plan · Act · Goal"]
        GOAL["Goal Driver<br/>wake · budget · checkpoint"]
    end

    LOOP["AgentLoop<br/>Model → Tool → Model"]

    subgraph TASK["任务智能"]
        direction LR
        PLAN["Plan Engine"]
        LEDGER["Task Ledger"]
        COMPLETE["Completion Contract"]
        HOOKS["Agent Hooks"]
    end

    subgraph SERVICES["Harness Services"]
        direction LR
        MEMORY["Memory Manager"]
        EVENT["Event System"]
        POLICY["Safety & Policy"]
    end

    ENTRY --> TURN
    TURN --> LOOP
    MODE --> LOOP
    GOAL --> TURN

    PLAN <--> LOOP
    LEDGER <--> LOOP
    COMPLETE <--> LOOP
    HOOKS -. observe / extend .-> LOOP

    LOOP --> MEMORY
    LOOP --> EVENT
    LOOP --> POLICY
```

---

# 3. Tool Call 与安全执行架构

```mermaid
flowchart LR

    MODEL["Agent / Model"]
    CALL["ToolCall"]

    REG["Tool Registry"]
    SCHEMA["Schema Validation"]
    CAP["Capability Resolver"]
    POLICY["Policy Engine"]
    APPROVAL["Approval Engine"]
    DISPATCH["Tool Dispatcher"]
    VERIFY["Result Verification"]
    AUDIT["Audit / Settlement"]

    subgraph TARGETS["Execution Targets"]
        direction TB
        E0["E0 Native<br/>Main App UID"]
        E1["E1 QuickJS<br/>Isolated Process"]
        E2["E2 Linux / PRoot<br/>Separate UID"]
        E2C["E2C Provider Adapter<br/>Separate UID"]
    end

    RESULT["Persisted ToolResult"]

    MODEL --> CALL
    CALL --> REG
    REG --> SCHEMA
    SCHEMA --> CAP
    CAP --> POLICY
    POLICY --> APPROVAL
    APPROVAL --> DISPATCH

    DISPATCH --> E0
    DISPATCH --> E1
    DISPATCH --> E2
    DISPATCH --> E2C

    E0 --> VERIFY
    E1 --> VERIFY
    E2 --> VERIFY
    E2C --> VERIFY

    VERIFY --> AUDIT
    AUDIT --> RESULT
    RESULT --> MODEL
```

---

# 4. Plan / Act / Goal 产品与状态架构

```mermaid
flowchart TB

    USER["User Request"]

    MODE{"Mode"}

    CHAT["Chat<br/>问答 / 低风险工具"]
    PLAN["Plan<br/>只读规划"]
    ACT["Act<br/>立即执行"]
    GOAL["Goal<br/>长期目标"]

    USER --> MODE
    MODE --> CHAT
    MODE --> PLAN
    MODE --> ACT
    MODE --> GOAL

    subgraph PLANFLOW["Plan Flow"]
        direction LR
        P1["Explore"]
        P2["Draft Plan"]
        P3["Review"]
        P4{"User Decision"}
        P5["Revise"]
        P6["Execute"]

        P1 --> P2 --> P3 --> P4
        P4 -->|Revise| P5 --> P2
        P4 -->|Approve| P6
    end

    PLAN --> P1
    P6 --> ACT

    subgraph ACTFLOW["Act Flow"]
        direction LR
        A1["Plan / Think"]
        A2["Tool Calls"]
        A3["Verify"]
        A4["Completion Report"]

        A1 --> A2 --> A3 --> A4
    end

    ACT --> A1

    subgraph GOALFLOW["Goal Flow"]
        direction TB
        G1["READY"]
        G2["RUNNING"]
        G3{"Needs User?"}
        G4["INPUT_REQUIRED"]
        G5["PAUSED"]
        G6["BLOCKED"]
        G7["COMPLETED"]

        G1 --> G2
        G2 --> G3
        G3 -->|Input| G4
        G3 -->|Pause / Budget| G5
        G3 -->|Blocked| G6
        G3 -->|Done| G7

        G4 --> G2
        G5 --> G2
    end

    GOAL --> G1
```

---

# 5. Prompt / Context / Model 架构

```mermaid
flowchart TB

    subgraph SOURCES["Context Sources"]
        direction LR
        SYS["System"]
        MODE["Mode Policy"]
        USER["User"]
        FILE["Files"]
        WEB["Web"]
        TOOL["Tool Results"]
        MCP["MCP"]
        SKILL["Skills"]
        PROJECT["Project Instructions"]
    end

    subgraph PROMPT["Prompt Assembly"]
        direction TB
        REG["Prompt Registry"]
        ORDER["Ordering / Scope"]
        TRUST["Trust Boundary"]
    end

    CTX["Context Engine"]
    COMPACT["Context Compaction"]
    REQUEST["Model Request"]
    ROUTER["Model Router"]
    PROVIDER["Provider"]

    SYS --> REG
    MODE --> REG
    SKILL --> REG
    PROJECT --> REG

    USER --> CTX
    FILE --> CTX
    WEB --> CTX
    TOOL --> CTX
    MCP --> CTX

    REG --> ORDER
    ORDER --> TRUST

    CTX --> COMPACT
    COMPACT --> REQUEST
    TRUST --> REQUEST

    REQUEST --> ROUTER
    ROUTER --> PROVIDER
```

---

# 6. 数据持久化与恢复架构

```mermaid
flowchart TB

    subgraph STATE["Persistent State"]
        direction LR
        SESSION["Session"]
        TURN["Turn"]
        CALL["ModelCall"]
        TOOL["ToolCall / Result"]
        APPROVAL["Approval"]
        GOAL["Goal"]
        TASK["Task Ledger"]
    end

    DB["Room Database"]

    subgraph FILES["File State"]
        direction LR
        WS["Workspace"]
        ART["Artifacts"]
        TEMP["Temp / Staging"]
    end

    CHECKPOINT["Checkpoint"]
    AUDIT["Audit Log"]
    RECOVERY["Recovery & Reconciliation"]

    SESSION --> DB
    TURN --> DB
    CALL --> DB
    TOOL --> DB
    APPROVAL --> DB
    GOAL --> DB
    TASK --> DB

    WS --> CHECKPOINT
    ART --> CHECKPOINT
    TEMP --> CHECKPOINT

    DB --> RECOVERY
    CHECKPOINT --> RECOVERY
    AUDIT --> RECOVERY

    RECOVERY -->|"resume"| TURN
    RECOVERY -->|"restore goal"| GOAL
    RECOVERY -->|"reconcile side effects"| TOOL
```

---

# 7. Android 平台集成架构

```mermaid
flowchart TB

    HELIX["Helix App"]

    subgraph ENTRY["Mobile Entrypoints"]
        direction LR
        SHARE["Share"]
        VOICE["Voice"]
        WIDGET["Widget"]
        ASSIST["Default Assistant"]
        NOTIF["Notification"]
    end

    subgraph ANDROID["Android Capabilities"]
        direction LR
        INTENT["Intent"]
        SAF["SAF / Files"]
        CAL["Calendar"]
        CLIP["Clipboard"]
        NOTIFY["Notification Access"]
        ACCESS["Accessibility"]
        ROOT["Root"]
    end

    subgraph SERVICE["Long-running Runtime"]
        direction LR
        FGS["Foreground Service"]
        SCHED["Scheduler"]
        BINDER["Binder / PFD"]
    end

    subgraph RUNTIME["Execution"]
        direction LR
        NATIVE["Native Tools"]
        QUICKJS["QuickJS"]
        PROOT["PRoot / Linux"]
    end

    ENTRY --> HELIX
    HELIX --> ANDROID
    HELIX --> SERVICE

    INTENT --> NATIVE
    SAF --> NATIVE
    CAL --> NATIVE
    CLIP --> NATIVE
    NOTIFY --> NATIVE
    ACCESS --> NATIVE
    ROOT --> NATIVE

    HELIX --> QUICKJS
    BINDER --> QUICKJS
    BINDER --> PROOT

    FGS --> HELIX
    SCHED --> HELIX
```

---

# 推荐文档组织

```text
docs/architecture/
├── overview.md
│   └── 图 1：总体架构
├── agent-harness.md
│   └── 图 2：Agent Harness
├── tool-runtime.md
│   └── 图 3：Tool 与安全执行
├── task-model.md
│   └── 图 4：Plan / Act / Goal
├── context-and-prompt.md
│   └── 图 5：Prompt / Context / Model
├── persistence-and-recovery.md
│   └── 图 6：数据与恢复
└── android-platform.md
    └── 图 7：Android 平台集成
```
