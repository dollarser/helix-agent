# Plugin Platform P0 / Mobile Use MVP 证据（2026-09-28）

## 范围

本记录只覆盖本轮 Plugin Platform P0 与 Mobile Use MVP，不把同一工作树中既有的
P5/tool-exposure/public-agent/AndroidWorld 未提交工作当成本任务交付事实。

本轮完成：

- 以 Agent Plugins 1.0 的 plugin.json + skills/ + mcp.json 作为 portable floor；
- 增加 host-native Plugin provenance 与 App 级唯一 PluginRegistry；
- 将 developer Android Accessibility automation 从 Built-in contribution 改为 Mobile Use Plugin contribution；
- 保持 TurnEngine、AgentLoop、ToolDispatcher、approval、effect truth 所有权不变；
- 审查 MCP、Skills、Connector、Marketplace、ToolRegistry 与 PluginRegistry 的长期边界。

调研与设计：

- ../../research/agent-plugin-ecosystem-and-mobile-use-2026-09-28.md
- ../../architecture/plugin-platform-refactor-2026-09-28.md

## Plugin Platform P0

新增 extensions:plugin，包含 PluginManifest / PluginManifestReader、PluginRegistry、
HelixPlugin 与 PluginToolBinding。

新增 ToolOrigin.PluginOrigin(pluginId, pluginVersion, runtimeId) 与 ToolCallSource.Plugin。
Plugin origin 自动进入既有 ToolDescriptor.contractHash，因此 Plugin version 或 runtime
binding 变化会形成新的 approval security contract；Plugin provenance 本身不降低风险。

PluginRegistry.register 在发布任何 descriptor 前预检 Plugin id、provenance、descriptor
identity 与 executor identity 冲突，避免 bundled Plugin 因 orphan executor 形成半注册。

PluginRegistry 已提升到 AppContainer 级唯一实例；native plugins 不再各自创建平行 registry。

## Mobile Use

新增 extensions:mobile-use。其 root manifest 位于：

    extensions/mobile-use/src/main/assets/plugins/mobile-use/plugin.json

manifest 使用 Agent Plugins 1.0 schema，并将 Helix 私有 runtime binding 放在：

    extensions.com.helix.agent.runtime = mobile-use

Mobile Use 复用既有 tools:automation，不重写 Accessibility runtime。模型工具保持九个：

- ui.snapshot
- ui.find
- ui.click
- ui.long_click
- ui.set_text
- ui.scroll
- ui.back
- ui.home
- ui.wait

既有 Accessibility capability、显式 package allowlist session、generation/token stale
protection、target-changed fail-closed、敏感 UI/action 拒绝、action/time budget 与
Dispatcher / Policy / Approval / Audit 唯一执行入口均保留。

## Agent Plugins package 入口

现有 ConnectorPackageReader 作为过渡入口增加 root plugin.json Agent Plugins 1.0 识别：

- 只接受 https://agent-plugins.org/schemas/1.0.0/plugin.schema.json；
- mcp.json 继续走既有 MCP endpoint 安全解析；
- skills/**/SKILL.md 继续走既有 immutable Skill snapshot；
- ZIP traversal、symlink、special file、size 与 compression-ratio 上限不变。

长期应将该 reader 从 extensions/skills.connector 提升为 Plugin package-import 层；
本轮没有继续把 Plugin 等同于 Connector。

## Extensions 统一审查

需要统一的是 Plugin 安装身份/版本、component ownership、install/update/uninstall
lifecycle、session/default selection 与 Marketplace 顶层模型。

以下实现保持独立：

- extensions/mcp：MCP protocol / handshake / egress / dynamic remote tools；
- extensions/skills：Skill snapshot / resource / enablement；
- ToolRegistry：所有来源共享的唯一 model-callable descriptor registry；
- Dispatcher / approval / effect truth：唯一执行边界。

ConnectorService 长期收窄为 endpoint/auth/OAuth/connect lifecycle；package
install/update 迁至 PluginInstallationService。Marketplace 长期转为 Plugin 顶层加
SKILL/MCP/NATIVE component badges。

另识别 P1 基础设施问题：ToolRegistry 与 ToolImplementationRegistry 当前为独立表，
MCP/A2A replace 与 Dispatcher resolve 均分两步。设计文档已记录后续 paired
ToolBindingRegistry 方向；本 P0 不在无运行缺陷证据下扩大 Dispatcher 核心重构。

## 主机验证

定向验证命令：

    ./gradlew :extensions:plugin:test :extensions:skills:test :tools:framework:test \
      :tools:automation:testDebugUnitTest :app:compileConsumerDebugKotlin \
      :app:compileDeveloperDebugKotlin :app:compileDeveloperDebugAndroidTestKotlin --no-daemon

结果：BUILD SUCCESSFUL，306 actionable tasks（34 executed / 272 up-to-date）。

APK 构建：

    ./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest --no-daemon

结果：BUILD SUCCESSFUL。

## API 36 owned emulator

使用 Helix_API_36、owned console port 5678，通过 scripts/run-owned-emulator.py
安装本轮 developer app/test APK，只执行 MobileUsePluginRegistrationDeviceTest。

结果：

    Time: 2.083
    OK (1 test)

真实 AppContainer 断言：

- pluginRegistry.find("mobile-use") 存在；
- version = 0.1.0；
- runtime = mobile-use；
- ToolRegistry 中恰好九个 ui.* contracts；
- 九个 tool 均来自 PluginOrigin("mobile-use", "0.1.0", "mobile-use")；
- contract hashes 均存在。

本地证据：

    build/mobile-use-plugin-device-20260928/instrumentation.txt
    build/mobile-use-plugin-device-20260928/artifacts.json
    build/mobile-use-plugin-device-20260928/closed.json

APK SHA-256：

- app: 90ca92db526e2d673ed8c48deb160b9395cb2ab4c047525ccd0780839bbd98d5
- test: 2763d9a81b58c8ba6d70fcf01f59487f80080ef04daf86b716ffcd867d1702be

owned emulator closure 为 pid 48160 / exit 0；验证后 adb devices 为空。

## 未宣称

本轮不宣称：

- 任意第三方 Kotlin/JAR/DEX/APK native Plugin 动态加载；
- Marketplace 已完成 Plugin-first 迁移；
- Connector storage 已迁移为 Plugin storage；
- remote Mobile MCP provider；
- screenshot / vision fallback；
- 物理真机/OEM Accessibility 验收；
- TalkBack 实际体验；
- Google Play Accessibility policy 可发行性；
- Tool descriptor/executor registry 已完成原子合并；
- push、merge 或 release。
