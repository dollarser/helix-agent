> 证据快照：本页的状态、分工、命令结果和设备仅对应记录时点，不作为当前开发指令；当前入口为[实施状态](../../development/status.md)。

# Helix 全仓优化改进审查（2026-09-10，第三轮）

文档状态：快照（review snapshot）
性质：在 [2026-09-06](improvement-review-2026-09-06.md) 与 [2026-09-07](improvement-review-2026-09-07.md) 两轮全仓审查之后，仓库经历 HXA-147～187 大波开发（会话/文件 UX、Goal ADR-GOAL-001、职责拆分 HXA-179/183/184、长稳取证 HXA-185、真机回归 HXA-186/187）后的第三轮全仓审查。本轮按四个维度并行扫描：**文档与流程 / 系统架构 / 产品设计与 UX / 工程最佳实践**，另对前两轮点名的文档治理项做了抽核。
**范围声明**：本轮不是对 09-07 P0/P1 清单（S1～S8、U1～U8、C1～C11、G-H1 等）的逐条回归核对；这些代码级项本轮未重新验证，状态以各自文档为准。§5 只列本轮证据直接覆盖到的前轮项。**不是当前状态源**，当前状态以 [status.md](../../development/status.md) 为准。

## 0. 审查元信息

- **审查日期**：2026-09-10
- **基线**：`main` `20bd6ae`（本地快进合入 `81d60e6`，未推送）+ 工作区 **82 个路径未提交**（docs 状态记录 + 测试夹具适配）。结论基于工作区（磁盘）状态。
- **方法**：四个领域并行只读审查（文档体系 / 系统架构 / 产品 UX / 工程实践），架构与 UX 域经 codegraph 索引交叉验证；文档域批量验证 1424 条相对 Markdown 链接（0 缺失）、逐条核对 42 个 ADR 状态链；工程域实测门禁脚本与 CI 配置。
- **门禁基线（status.md HXA-184 记录）**：主机 2788 通过 / 8 项外部条件跳过；API29 consumer / API36 developer 各 394 通过、2 项浏览器长稳/诊断条件跳过。本轮未重跑门禁。

## 1. 总体评价

仓库的工程纪律与文档治理在同类项目中属少见的高水平：链接治理 0 缺失、ADR 六字段与 superseded 双向链完整、依赖签名校验 + `lockAllConfigurations` + 35 模块 lockfile、CI 全 pin SHA、模块依赖方向无环、fail-closed 设计贯彻始终。架构层面 HXA-179/183/184 的职责拆分是**收敛而非膨胀**——生产 top 类从多个 >1000 行降到只剩 ChatService 一个 >1500，且无状态双源残留。

但两个结构性问题贯穿本轮：

1. **审查闭环的"消费修复"失速**——两轮审查点名的文档治理项（status 瘦身、ADR 状态机械检查、进展文档登记、check-lockfiles 魔法数、CI 缺 check-i18n）至今大多原样保留，且 HXA-185 状态失同步证明"多源未同步"同类问题仍在新增（§3 H1/H2/H3）。
2. **短板集中在"分发侧与最后一公里"**——release 无 R8、第三方许可证清单不完整（§6 H1/H2）；新用户从安装到第一个成功任务无一条被引导的路径，三个核心系统权限无 in-app 获取入口（§4 必须修 1/2）。

---

## 2. 系统架构

### 高

**A-H1. 三个 runtime 的跨进程协议栈各自一套，且已出现行为漂移**
- 有界 PFD 管道通道复制两份且 close 语义不一致：`runtime/proot-ipc/.../PfdManifestChannel.kt:53-77` 的 `readFromStart` 用 `AutoCloseInputStream` 但**没有显式 close**，依赖 finalizer 释放 FD（KDoc 声称"reads one complete manifest … and closes it"，与实现不符）；`runtime/cli-client/.../CliPfdChannel.kt:8-24` 同构逻辑却用 `.use` 确定性关闭。两段 ~110 行近乎同构，属安全敏感路径（DoS 上限强制、FD 释放），修一处必须人肉同步另一处。
- 同类重复：版本化 transact + 状态字握手（`ProotHandshakeClient` 129 行 vs `CliStatusHandshakeClient` 57 行，DeadObjectException/RemoteException→稳定状态映射各写一遍）；deadline/轮询/对账（`CliModelJobAwaiter` vs `JsExecutionClient` 内联 `awaitResult` vs `ProotJobClient`）。
- **建议**：quickjs 用 Parcel 而非 PFD 是正当差异（同 APK 同 UID 隔离进程），不应强并；但 proot 与 cli 是跨 UID 同构场景，值得抽共享 transport 层——只下沉管道通道与握手/超时原语，事务码/manifest schema/作业状态机保持独立。`proot-ipc` 双 APK 共享模块先例已证明可行。

### 中

- **A-M1 ChatService 仍是唯一 >1500 行的生产类**（`app/.../chat/ChatService.kt` 1736 行 / 80 fun）。HXA-183"不把状态机机械切散"，1811→1736 属有意收口；状态源仍单一（抽取的 `ChatDraftStore`/`TurnCoordinator`/`GoalRunCoordinator` 各持窄状态，无重复所有权证据）。剩余可拆候选只有会话准入与发送管线；建议随下一个自然需求点顺带做，**不立专项**。
- **A-M2 3 个 spikes 模块仍在主构建**（`spikes/a2a-sdk`、`a2a-minimal`、`bounded-orchestration`，各带独立 lockfile + androidTest），仅由 `scripts/check-a2a-*-spike.sh` 手动驱动，status/roadmap 均无条目；A2A 已生产化为 `extensions:a2a`。建议移出 `settings.gradle.kts`（独立 settings 或归档），同步改 `check-lockfiles.sh` 清单。
- **A-M3 eval 评测台架内嵌 app 的 developer instrumented test**（`app/src/androidTestDeveloper/.../eval/` 已 27 文件），而 `testing/` 模块只有 1 文件。台架是独立变化原因，建议基类/端口下沉 `testing/`，app 只留用例。
- **A-M4 根 build.gradle.kts（594 行）集中配置全部 33 个模块**（除 3 个 APK 模块外无自己的 build 文件，`subprojects { when(path) }` 级联）。基线统一是收益；合并冲突热点是代价。若 per-path 分支继续增长应抽 convention plugins（build-logic）。当前可维护，预防性事项。

### 低（无需动作，记录健康项）

- 模块依赖方向干净：`core:model ← policy/storage/agent/workspace ← tools:* ← feature/extensions ← app`，无环无反向 import；`feature:browser → tools:browser` 是已文档化的 port 反转。
- flavor 分叉受控：7 组同 FQN 切换对 + marker 注册 + `scripts/verify-variant-boundaries.sh` dex 探针。只需防止第 8 组悄悄出现。
- 组合根健康：`AppContainer`（119 行接口）+ `DefaultAppContainer`（566 行）。
- 测试分布健康：JVM 237 / androidTest 274 文件，各模块两侧覆盖无偏科。

## 3. 文档体系与开发流程

### 高

**D-H1. status.md 内部陈旧自相矛盾，"唯一当前状态源"可信度受损**
- `status.md` 第 3 行称"HXA-161～184 已提交并快进合入本地 main"，但 189–211 行 HXA-161～172 各 bullet 仍逐条写"尚未提交/合入 main/推送"——合并后未回写。
- 第 72 行声明"本文件不复制字段级历史"，但 86–238 行约 150 行 Completed bullets 逐条复述完成记录的命令与测试计数，M5/M5A/M5B/M6 表格单元格单行数千至 9000+ 字符。09-06 P4#4 与 09-07"#17 大表重复维护"均已点名，未修。
- "最新回归"行停在 HXA-184，而 In progress 节已有 HXA-185 更新证据。
- **建议**：Completed bullets 瘦身为"HXA 号 + 一行范围 + 链接"；回写已合并项；最新回归行更新。

**D-H2. roadmap 与 status/矩阵对 HXA-185 状态矛盾**
- `roadmap.md` 第 1074 行仍写"状态：in progress（代码已写入，待 Claude 验证）"；verification-matrix 321/327 行标"已完成"、completion-records/HXA-185.md 存在、status.md 称验证完毕。AGENTS.md 要求新会话同时读 status 与 roadmap，两源矛盾会直接导致接错任务（09-07 D-H1 失效模式的复现）。
- **建议**：roadmap HXA-185 改 completed 并链完成记录。**这是下一个 HXA 的第一件事**——它影响任务接续机制本身。

**D-H3. superseded/已接受 ADR 的过期状态引用未清账，机械检查两轮未落地**
- `roadmap.md` 第 650 行仍写"依 **accepted** [ADR-PROVIDER-002]"（0024 已被 0025 superseded）；`completion-records/HXA-088.md` 第 11 行"ADR-WORKSPACE-003 保持 proposed"无后续指引（0008 已 accepted）；`adr/0008-git-workspace-management.md` 第 59 行小节标题"Status 仍为 proposed"与文件头 `accepted` 自相矛盾。
- 09-07 P5#2 要求在 check-docs/verify-adr 增加"文档中 ADR 状态词与文件头一致"机械检查；当前 `scripts/check-docs.sh` 对 ADR 零检查。同类问题两轮已发现 6+ 处，人盯不收敛。
- **建议**：三处一次性清账 + 落地机械检查（投入最小、防复发收益最大）。

### 中

- **D-M1 里程碑缺口**：roadmap §1 总览与 §2 退出条件表无 M11A 与 M14；status.md 却以"M14"名义列 HXA-148～151；verification-matrix 无 M14 痕迹。
- **D-M2 completion-records 无索引 + 膨胀**：`completion-records/README.md` 仅 12 行规则、无索引，但 status.md M1～M4 四处将其标为"[逐 HXA 索引]"权威证据——被指向的文件里根本没有索引。160 份记录/1.9 MB 只能靠 ls/grep；HXA-102.md 已 1642 行，违背同目录 README"不把大段后续修复史追加到旧交付快照"。
- **D-M3 提交文档链接 gitignored 制品**：`verification-matrix.md` 第 329 行链 `../../build/emulator-verification/run-index.json`，`git check-ignore` 确认 IGNORED——本工作树可解析，fresh clone 即断链；check-docs.sh 只做本地解析发现不了。
- **D-M3b 未跟踪 bug-fix 文档链不存在的完成记录（落盘时新增，实测）**：`bug-fixes/2026-09-10-root-service-death-callback-reentrancy.md:49` 链 `completion-records/HXA-094.md`，该文件不存在（HXA-093 后直接是 HXA-096，HXA-094 未验证、无完成记录，见 status.md Blocked 表）。当前 `check-docs.sh` 因这条断链整体 FAIL——在 HXA-094 记录补齐或改链前，文档门禁无法通过。
- **D-M4 孤儿文档**：`m9-rooted-emulator-experiment.md`、`hxa-146-progress.md`、`verification-gaps-progress.md` 全仓无入链；docs/README.md 无"进展/交接文档"登记小节，development/ 下约 20 份进展/交接/快照文档游离于文档中心（09-06 L3/L4、09-07 D-M5 连续两轮点名）。
- **D-M5 编号治理缺失**：HXA 空号 017–019、029、089、098、106–109 无说明；m11-main-numbering.md 证明占号冲突已实际发生。

### 低

- **D-L1 AGENTS.md 遗留过去式门禁**：第 9 行"HXA-077 remains the mandatory … Spike, and HXA-078/079 cannot start until it records…"——M7 已全部交付，应删除。
- **D-L2 scripts/ 组织**：顶层 70 个文件混放常设门禁、一次性验收脚本（accept-hxa-083/086/087/124/125×3/148/149/150）与长稳 runner，无 README、无一次性脚本归档约定；工作树出现未跟踪 `scripts/__pycache__`（建议 gitignore）。
- **D-L3 history/ 层闲置**：docs/history/ 仅 1 份；m10-closure-followup.md（1510 行）等已失去当前效力的长日志仍留在 development/，与分层意图不符。

### 做得好（本轮验证）

- 1424 条相对链接 0 缺失；docs/README 目录职责表与实际目录吻合。
- 42 个 ADR 六字段齐全，6 组 superseded 双向链（0002↔0010、0005↔0012、0006↔0013、0024↔0025、0028↔0040、0037↔0038）全部一致。
- 快照横幅纪律良好；verification-matrix 对 in-progress（HXA-188/185/186/187）覆盖完整。

## 4. 产品设计与用户体验

### 必须修（阻碍发布 / 目标用户核心场景）

- **P-H1 首次启动无 onboarding**：首启只有一屏静态通知（`ui/FirstLaunchNoticeScreen.kt`），点"继续"即进聊天；Provider 配置埋在设置→Provider 区，空态文案是纯文本无跳转按钮。市场文档 §7.1 定义的"模板→连接测试→示例 Workspace→首个任务"首次价值路径未实现。**激活指标最大单点**。
- **P-H2 权限中心缺失**：需求 §7.7 要求权限中心；实现上抽屉"权限"入口在 consumer 是诚实空页（`MainActivity.kt:298-304`）。Notification Listener/Calendar 服务在 `tools/android` manifest 声明但 UI 无深链引导；`POST_NOTIFICATIONS` 只读状态（`SystemCapabilityResolver.kt:142`）从不发起请求 → **API 33+ 上 Goal 提醒通知（`GoalReminderWorker.kt`）静默失效**。UJ-02/UJ-03 两条 P1 旅程对新用户实际走不通。
- **P-H3 Advanced 说明文案过期且错误**：`strings.xml` 的 `settings_advanced_m2_note` 仍是"M2 说明：切换 Advanced 不启用任何新能力——零系统权限申请……"，但当前构建切换后实际显示 PRoot/Root/Automation/Egress 区块（`SettingsScreen.kt:128-164`）。用户可见、事实错误、含内部里程碑黑话。**改动成本极低，当天可完**。
- **P-H4 无深色模式**：主题固定亮色（`themes.xml` 硬编码状态栏/导航栏色），全仓无 DynamicColor/darkTheme。建议排进发布批次。

### 可延后

- 无备份/迁移：manifest 无 backup 规则，无会话/Provider 配置导出，换机即丢配置投入。
- 无会话/历史搜索：只有文件管理器搜索；会话列表不可检索。
- consumer 版"扩展"页名不副实：Skill 区为 null 时只剩 Connector 段 + 两条孤立分隔线（`ExtensionsScreen.kt:37-43`）。
- **审批卡是 14+ 行平铺文字墙**：L0/L1 低风险卡与 L2 代码执行卡同构全量平铺，无折叠分层；`ExpandableSummary.kt` 已存在却未用于卡片字段。核心差异化界面反而对普通用户过载。建议用既有组件做分层折叠。
- **审批卡英文硬编码**："Safety Profile"、"Provider/MCP" 标签（`ApprovalCardScreen.kt:90-91`）在中文 UI 不翻译——`check-i18n.sh` 只扫 CJK 字面量，**英文硬编码是门禁盲区**，建议补英文字面量检测。

### 做得好（本轮验证）

- 审批卡字段纪律：恰好两个按钮、无永久放行、无模型自批准、编译期绑定 ACTIONS（与 ADR-PERMISSIONS-003 一致），真实差异化。
- 出网披露对话框（Provider/协议/规范 origin/数据驻留/逐附件名·类型·大小）对应 FR-LLM-010。
- Provider 连接测试五阶段分层 + 错误码本地化，满足 FR-LLM-004。
- 三态总体健全（抽 7 屏）；崩溃/中断恢复用户可见（turn 中断→显式恢复/丢弃；文件传输中断持久记录与恢复，HXA-182）。
- consumer 构建 fail-closed 诚实空态：不伪造被门控的能力。

## 5. 前两轮点名的项——本轮证据覆盖到的回归状态

| 项 | 前轮提出 | 本轮状态 |
| --- | --- | --- |
| status.md 瘦身 / 大表重复维护 | 09-06 P4#4、09-07 #17 | **仍未修**（§3 D-H1），且新增合并标记未回写 |
| ADR 状态词机械检查 | 09-07 P5#2 | **仍未落地**，check-docs.sh 对 ADR 零检查；3 处过期引用新增（§3 D-H3） |
| 进展/交接文档登记 | 09-06 L3/L4、09-07 D-M5 | **仍未落地**，孤儿文档 3 份 + 游离约 20 份（§3 D-M4） |
| check-lockfiles 硬编码 35 | 09-07 工程项 | **仍未修**（§6 E-L2） |
| CI 缺 check-i18n 门禁 | 09-07 工程项 | **仍未修**，CI 只跑 lockfiles/secrets/adr/docs/variant-boundaries 五脚本（§6 E-M1） |
| ChatService 拆分（3244 行，09-07 恶化项） | 09-07 §2.4 | **已显著收敛**：1736 行，拆分无状态双源残留（§2 A-M1） |
| 大类职责审查 | 09-10 audit | HXA-183/184 已收口，审计文档在位；剩余类较长不自动新增拆分任务 |
| S1～S8 / U1～U8 / C1～C11 / G-H1 等 09-07 P0/P1 代码项 | 09-07 | **本轮未重新验证**，状态以 09-07 清单及后续 HXA 记录为准 |

**结论**：文档治理类"发现→复核"闭环的前半程很强，**消费修复明显失速**；代码级 P0/P1 是否已消费需专项回归，建议下一轮审查恢复 09-07 式的逐条核对。

## 6. 工程最佳实践

### 高

- **E-H1 THIRD_PARTY_NOTICES.md 覆盖不完整**（根目录，仅 51 行）：逐一详列的只有 libsu 6.0.0 与 ExifInterface 1.4.2，实际打包的 Compose BOM、OkHttp 5.5、Room 2.8.4、Ktor 3.5、MCP kotlin-sdk 0.15、a2a-java 1.3.1、Zipline 1.27 等核心依赖均无条目。走任何分发渠道都是法务缺口。**建议**：Gradle task 从依赖树自动生成许可证清单，CI gate 其与 NOTICE 一致。
- **E-H2 release 构建没有 R8**（`app/build.gradle.kts:31-37` `isMinifyEnabled = false`），且 CI 只 `assemble*Debug`，Release 变体从未被完整构建/校验。带 root/PRoot 组件的应用缺混淆与 shrink 验证，既是体积也是安全问题。发布前必须补齐。

### 中

- **E-M1 门禁无一键入口，本地与 CI 集合不一致**：scripts/ 约 70 个脚本无 Makefile/justfile/check-all；`check-i18n.sh`（236 行）、`check-cli-runtime-boundary.sh`、`check-cli-runtime-lock.sh`、assetGate 均不在 CI。**低成本高收益**：建 `scripts/check-all.sh` 让 CI 与本地共用同一列表。
- **E-M2 测试 flake 无系统性隔离/重试**：所有 .kts 无 `maxRetries`；docs 多处"环境 flake 如实记录"靠散文。2788 项主机 + 394×2 设备规模下不可持续。**建议**：`Test.retries`（JVM）+ 设备侧失败自动隔离复跑一次的包装层，把 flake 证据结构化。
- **E-M3 无依赖漏洞扫描**：CI 有完整性校验（强）但无漏洞面（osv-scanner 等均无）；snakeyaml-engine 3.1.1、commons-compress 1.28.0 这类组件历史上出过 CVE，纯锁版本不等于安全。**低成本高收益**：CI 加 osv-scanner 步骤（对 lockfile 扫，无需凭据）。
- **E-M4 spikes/ 仍是一等构建模块**：占 CI lint/assemble 面与 lockfile 维护成本（与 §2 A-M2 同一项，建议一并处理）。

### 低

- **E-L1 check-secrets.sh 纯正则、无历史扫描**（45 行）：fail-closed 设计好（rg 失败即拒、匹配输出抑制不回显密钥）；无熵检测、不扫 git 历史、无 allowlist（`sk-…`/JWT 型模式在 fixture 中易误报，未来会倒逼豁免）。可选：CI 加 gitleaks/trufflehog 历史扫描（一次性，成本低）。
- **E-L2 check-lockfiles.sh 硬编码 "Expected 35 dependency lock files"**：可从 settings.gradle.kts 的 include 列表派生，消除魔法数（同时解决 spikes 移除时的联动改动）。
- **E-L3 CI 无 androidTest 执行**：设备测试完全依赖本地/手工（结合 3 台模拟器 ~96% RAM 偶发 flake 的环境约束可以理解；若有设备池值得评估）。

### 做得好（本轮验证）

- 依赖可信链超过多数中大型项目：`verification-metadata.xml` 签名校验 + `lockAllConfigurations` + 35 模块 lockfile + `check-lockfiles.sh` 哈希快照。
- `.github/workflows/ci.yml`：action 全锁 SHA、concurrency 取消，注释解释每个设计决策。
- 仓库卫生干净：`build/` 零跟踪、`local.properties` 未跟踪、PRoot RootFS 二进制禁入库、`.git` 仅 82 MB、提交粒度健康（feat/fix/test/docs + scope）。
- `SecretStore.kt`（Keystore 主密钥 + AES-GCM、fail-closed、`SecretAlias` 间接引用）模式全库统一；detekt 配置无 baseline、无堆积豁免。

## 7. 亮点汇总（无需动作）

1. 文档链接治理与 ADR 状态机是当前体系最强的一环（1424 链接 0 缺失、42 ADR 状态链全一致）。
2. 审批卡 / 出网披露 / 连接测试三处安全 UX 是真实差异化，i18n 三套键 parity + CJK 门禁覆盖全部 UI 模块。
3. 架构拆分收敛：HXA-179/183/184 后无状态双源，组合根与共享 IPC 模块均朝健康方向。
4. 依赖锁定与 CI pin SHA 的供应链纪律。
5. fail-closed 诚实空态与用户可见恢复（turn 中断、文件传输中断）。

## 8. 建议行动序

**发布前必修（先做）**
1. P-H3 Advanced 文案修正（当天可完）
2. D-H2 roadmap HXA-185 状态回写（下一个 HXA 第一件事）
3. P-H1 onboarding 引导（模板→连接测试→示例任务）
4. P-H2 权限中心 + `POST_NOTIFICATIONS` 请求
5. E-H2 release 开 R8 + CI 补 Release 变体构建
6. E-H1 THIRD_PARTY_NOTICES 自动生成 + CI 门禁

**工程门禁第二批**
7. D-H3 ADR 状态机械检查 + 3 处清账
8. E-M1 `scripts/check-all.sh` 统一入口
9. E-M3 CI 加 osv-scanner
10. A-H1 proot/cli 共享 transport 层（含 `PfdManifestChannel.readFromStart` 显式 close 修正）

**中期**
11. A-M2 + E-M4 spikes 移出主构建（联动 E-L2 魔法数派生化）
12. A-M3 eval 台架下沉 testing/
13. UX：审批卡分层折叠 + i18n 门禁补英文字面量扫描；深色模式进发布批次
14. E-M2 flake 结构化（retries + 隔离复跑）
15. D-H1 status.md 瘦身（可与下一个文档类 HXA 一并收口，顺序 H2→H1→H3→M1/M2）

**低成本快赢（随时可做）**：`check-lockfiles` 魔法数派生、`.gitignore` 加 `scripts/__pycache__`、删 AGENTS.md 第 9 行过去式门禁、D-M3 断链修复。

**下一轮审查**：恢复 09-07 式对 P0/P1 代码项的逐条回归核对（本轮未覆盖）。
