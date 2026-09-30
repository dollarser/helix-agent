# Provider 模型管理统一：竞品依据、实现与验证

> 本页保留模型管理阶段的基线、命令和提交状态。后续所有者授权的使用链路修复、联合验证与整理提交见[收口记录](provider-chain-closeout-2026-09-30.md)，不从本页历史“未提交”推断当前工作区。

## 授权与基线

2026-09-30 所有者要求优化 API/自建服务、订阅与设备内模型的管理界面，并参考主流竞品。基线为 `d9bac5019aca63b14d2cee47f8a30ed9091681f4` 加此前 Runtime F1～F6 修复工作树；本记录不把先前未提交的 Runtime 修复算作 Provider 新增工作。保留现有工作，当前任务不提交、不推送。

设备、真实订阅/API 账号与本地真实模型验证：**not requested**。外部浏览仅用于阅读公开产品文档，未使用用户模型凭据。实现不修改认证协议、不把 token 移到 UI、不改变 Runtime 执行隔离或工具权限。

## 竞品依据与取舍

以下为 2026-09-30 查阅的官方文档；它们是交互和分层的依据，不是源码复制来源，也不表示 Helix 已实现竞品的所有功能。

| 来源 | 文档中可核验的行为 | 本次采用 | 没有照搬的部分 |
| --- | --- | --- | --- |
| [VS Code / Copilot Language Models](https://code.visualstudio.com/docs/agent-customization/language-models) | 统一模型编辑器；模型目录、选择器可见性分开；支持搜索、显示/隐藏和固定常用模型 | 三类来源共用模型管理；显式候选、搜索、顺序调整；模型条目显示来源 | 不实现自动模型路由、额外计费估计或独立收藏分组；顺序操作只把当前来源所选模型移至首位 |
| [Cherry Studio 自定义服务商](https://docs.cherryai.com.cn/pre-basic/providers/zi-ding-yi-fu-wu-shang) | 获取、添加、移除模型；支持手动 ID；建议按实际需要添加而非全部添加 | 完整目录独立于已选列表，网络来源可添加自定义 ID，刷新不隐式全选 | 不将未经检测的模型当作已支持工具，也不把目录缺失直接判定为不可用 |
| [LM Studio Per-model Defaults](https://lmstudio.ai/docs/app/advanced/per-model) 与 [Basics](https://lmstudio.ai/docs/app/basics) | 每模型保存配置；本地模型下载、选择、加载和运行有不同阶段 | 共用逐模型上下文入口；保留资产/候选/内存状态差异；使用友好名称 | 不增加新推理后端、自动下载或自动卸载；不扩大现有设备内模型参数范围 |
| [OpenCode Models](https://opencode.ai/docs/models/) | 按 provider/model 精确选模，支持模型级选项和默认目标 | 保持来源身份与精确模型 ID；逐模型检测不修改来源基础模型，不把 A 的能力推给 B | 不把所有认证方式硬塞进同一种传输协议，不自动启用目录模型 |

设计裁决继续使用 [ADR-PROVIDER-001](../../adr/provider/001-models-and-connection.md)。接入方式决定认证、账号、资产和 Runtime 管理；候选、默认、上下文和验证使用共同语义。

## 发现与修复

### 目录、选择、默认和证据分离

`ProviderSelectedModels` 现在保存一个选择文档：已选有序列表、可空默认模型、自定义模型与是否已配置。显式保存空列表不同于尚未配置；两者都不能回退为整个远端目录。默认模型必须在所选列表中，移除默认会清除默认，不偷偷选中另一项。设为默认是明确动作，同时将目标加入候选。

原先已经保存的非空显式选择被保留；旧的空列表没有“全选”含义。新 API 接入中手输的基础模型是一次明确初始选择；新 API 表单仍要求一个用于接入校验的模型。已有来源的候选管理允许保存空选择。订阅初始化的基础模型和本地已安装资产不因此自动进入候选。

完整目录及精确模型测试存放在独立的 `ProviderModelEvidenceStore`。目录刷新不修改选择；选择保存不改变来源连接状态、模型能力或当前会话。通过一次选择文档写入和旧快照核对，防止旧对话框覆盖其他入口的新选择。

### 统一管理界面与会话选择器

三个来源入口保留，都增加“管理会话模型”。同一个 `ProviderModelsDialog` 支持模型搜索、仅看已选、清空、默认选择、将已选模型移至首位、展开配置/验证以及保存后用于当前会话。网络来源支持显式刷新和手动模型 ID；设备内来源不允许添加任意未安装模型 ID。

`ComposerModelMenu` 改为搜索加懒加载列表，只取 `conversationModels`，不拼接完整目录或基础模型。当前会话使用但已隐藏的模型仍保留显示，并明确说明不会自动换模；它不被插回候选。来源不可用和候选选择是不同状态。UI 与交互选择服务共用候选规则；显式指定目标的内部建会话 API 不枚举目录，不把候选偏好当作授权。

本地模型以用户/目录名称作为主要标签，内部资产哈希仍用于精确绑定。安装、加入候选、释放内存、删除文件保持独立。仅“加入候选并用于当前会话”明确添加本地资产选择；其他下载或卸载动作不隐式改变候选。

### 接入设置与逐模型验证

API 现有来源编辑页只管理名称、地址、协议、Key 和 Header，不再夹带模型多选；候选/默认单独保存。仅修改显示名称保留连接证据；地址、协议、凭据或真实请求配置改变仍失效并重新验证。

`runConnectionTest` 与 `runCapabilityTest` 支持精确 `providerId + modelId`，使用临时模型目标配置，不写回 Provider 基础模型。能力检测票据按模型分开，配置修改统一废止旧票据。非默认模型的测试、上下文和推理能力使用自身记录；检测 B 不覆盖 A 的能力。连接探测不能用较早的能力快照覆盖另一路更新。

明确的 API 手动视觉声明同步更新对应模型证据，避免旧探测记录遮蔽用户声明；仍显示手动来源，不将声明变为实测证明。本地运行配置变化使该资产的精确能力记录失效，不从旧默认快照恢复。

## 关键实现入口

| 层 | 入口 |
| --- | --- |
| 用户偏好和候选 | `ProviderSelectedModels.kt`、`ProviderUiModels.kt` |
| 独立目录与精确证据 | `ProviderModelEvidenceStore.kt`、`ProviderConnectionProbe.kt`、`ProviderProbeGate.kt` |
| 接入与选择服务 | `ProviderService.kt`、`ProviderConnectionIdentity.kt`、`SupportedReasoning.kt` |
| 模型管理 | `ProviderModelsDialog.kt`、`ProviderModelChoiceRow.kt`、`ProviderScreen.kt`、`ProviderRow.kt` |
| 接入/本地/参数 UI | `ProviderFormDialog.kt`、`LocalModelDialog.kt`、`ProviderContextDialog.kt` |
| 会话 | `ComposerModelMenu.kt`、`ChatService.kt` |

没有新建 Provider 框架、执行域、依赖仓库或数据库迁移系统。沿用现有 LineStore 公共配置、服务、门禁和 ModelProvider 契约。

## 回归与当前验证

主机回归集中在 `ProviderModelSelectionTest` 与 `ProviderModelEvidenceTest`：显式空选择、全部来源候选一致、默认约束、自定义模型与顺序、过期保存、非法输入、配置失效不删除偏好、目录更新独立、来源/模型/endpoint 证据隔离、非默认工具与推理能力、连接与声明区别、手动视觉声明、畸形数据和名称改动。

AndroidTest 新增 `ProviderModelsIntegrationDeviceTest` 的真实 Room/Service/gate 组合和 `ProviderModelPickerDeviceTest` 的 Compose 选择器用例。测试使用合成 Provider，不需要真实服务；文件存在或编译成功不记作设备通过。

最终联合门禁退出码 **0，BUILD SUCCESSFUL**：1013 个任务中 57 executed、956 up-to-date（2m 4s）。受影响 App 单元测试、AndroidTest Kotlin 编译、lint 与 APK 重新执行，其余兼容结果由 Gradle 复用；不能表述为所有用例最后一轮都强制重跑。

```bash
python3 scripts/with-host-slot.py -- ./gradlew \
  test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin \
  :runtime:quickjs:compileDebugAndroidTestKotlin :runtime:cli-client:compileDebugAndroidTestKotlin \
  :runtime:proot-app:compileDebugAndroidTestKotlin \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --continue --configure-on-demand --no-configuration-cache --console=plain
```

| 精确报告 | tests（含 skipped） | failures / errors | skipped |
| --- | ---: | ---: | ---: |
| App Consumer Debug | 1010 | 0 / 0 | 4 |
| App Developer Debug | 1062 | 0 / 0 | 4 |
| ProviderModelSelectionTest（两渠道共享） | 10 | 0 / 0 | 0 |
| ProviderModelEvidenceTest（两渠道共享） | 7 | 0 / 0 | 0 |

本次新增 **17 个独立主机回归用例**，全部执行通过，不将两渠道重复运行加倍计数。新增 **5 个 AndroidTest 用例**（Room/service 3、Compose picker 2）已在两渠道编译，未执行设备测试，不能记录为通过。可选 Spike 集合未启用，也未执行 release 或真实账号验收。

精确报告时间、源文件 SHA-256 和统计记录保存在 `build/provider-model-management-2026-09-30/host-summary.json`，由 `scripts/debug/2026-09-30/summarize-provider-model-management.py` 读取明确的当前测试目录，不扫描历史归档。中途编译标点、Compose/测试格式和方法复杂度问题均已修正并通过最终检查；没有删除失败用例、增加跳过或放宽全局门禁。

联合构建后执行 `./scripts/check-all.sh --source`，退出码 0：655 Markdown、215 HXA、35 当前 ADR；839 个生产源码扫描通过，base/en/zh-rCN 共 1879 个资源键一致，秘密扫描通过。`git diff --check` 用于最终差异格式核验。新增 29 个界面资源键均有中英文，不复用硬编码的提示文本。

## 明确保留的边界

订阅适配器可返回的目录范围未被扩张：完整认证目录与单一配置模型回退仍按实际适配器能力区分；界面提示目录可能不完整，手动 ID 不证明账号权限。没有添加账号额度查询、自动检测全部模型、未经授权的收费生成或自动更换账号。

来源的登录/退出、设备内模型的下载/加载、实际性能与逐模型真实 Agent 完成率仍需对应环境验收。本轮不以代码检查宣称窄屏/无障碍/触控体验已经实机通过，也不把完整 Runtime/OEM 故障矩阵随本变更关闭。
