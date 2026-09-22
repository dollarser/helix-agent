# HXA-129：Connector 安装与会话来源验收

日期：2026-09-23。分支 `codex/hxa-129-connector-lifecycle`，开发基线为本地 main `72d1c4ca`。ADR 接受提交为 `d299dd82`，实现提交为 `f2f1ec5e`。本记录为本地实现验收，不代表 main 整合、推送或远端 CI。

## 已验证的实现边界

- Room schema 26→27：`connector_installations` 是唯一安装提交事实；另外保存 Skill 独立归属、会话选择、旧 JSON 导入标记及受管理端点 ID。端点 ID 保留用于拒绝旧 schema 和幂等清理，不是多阶段事务日志。
- 候选 Skill 是不可变文件，只有已提交来源才可用。复用 SkillRepository 的互斥锁保护准备至提交期间的快照引用；清理在锁内重新检查独立归属，避免独立安装与包清理的竞态。
- Room 发布核对预期 revision；同身份同 hash 幂等返回。旧版本在提交前保留，提交后的清理失败不撤销新版本；清理状态可见并能重试。进程死亡遗留的本次安装暂存目录也会清理。
- 端点只在 URL、认证要求与外来认证配置指纹一致时复用原 ID。仅保存配置摘要，不保存原始认证字段；外来认证材料不作为登录状态导入；变化端点使用新 ID、重新配置。仍在执行的 MCP 调用持有引用，结算前不删除其配置与 Secret。
- 会话选择与新会话默认值独立；fork 复制选择快照，更新保留身份，卸载后的选择保持不可用，新安装不按名称继承。Skill 读取使用可信 `ExecutableToolCall.sessionId`，MCP 曝光、Dispatcher、连接前与发送前共同检查来源。
- `tools/call` 前最后一次检查是本地发送准入点。关闭先完成则拒绝/取消；发送准入先完成则按原绑定结算。它不保证用户关闭后网络绝不会再发出字节，也不撤回远端效果或删除历史。
- 旧 JSON 只导入一次；无法证明包专属的旧 Skill 保留独立归属。旧市场记录只有来源为 MARKETPLACE、名称和当前内置目录内容 hash 精确匹配时才认领市场身份；其他旧记录保留原安装身份，可手动替换，不按名称猜测。
- UI 提供会话选择、默认集合、配置入口和文件更新；更新显示 hash、组件数量变化、可用的版本标签及作者说明。标签最多128字符，说明最多2048字符；没有说明时明确显示缺失。不提供代码 Diff 或版本历史浏览器。

## 命令与结果

环境为 Java 17 和已配置的 Android SDK。重型工作通过共享 host slot 串行；日志和冻结 APK 保存在忽略的 `build/hxa129/`。

```sh
python3 scripts/with-host-slot.py -- ./scripts/check-all.sh --all
python3 scripts/with-host-slot.py -- ./gradlew spotlessApply \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  :core:storage:assembleDebugAndroidTest :extensions:skills:test detekt
python3 scripts/with-host-slot.py -- python3 scripts/accept-hxa-129-connectors.py \
  --output build/hxa129/matrix-r2
```

- 开工基线：`build/hxa129/baseline.log`，完整主机门禁通过。
- 最终主机门禁：`build/hxa129/full-host-r3.log`，exit 0；格式、Detekt、多变体 Lint、全模块 JVM 测试、debug/release 构建、36份依赖锁、APK flavor/进程边界及订阅 Runtime 边界通过。
- 收口文档后的 `./scripts/check-all.sh --source`：`build/hxa129/final-source-r2.log`，exit 0；524份Markdown、202项HXA、36份ADR、1607组资源键及Secret扫描通过。
- 设备矩阵：`build/hxa129/matrix-r2/summary.json`，六批 exit 0；四象限各46项应用回归、3项强杀恢复，双 API 各38项存储测试，合计272项通过。

| 设备 | 应用回归 | 安装中强杀后的新进程验证 | 存储迁移/回归 |
| --- | --- | --- | --- |
| API29 consumer | 46/46 | 3/3 | — |
| API29 developer | 46/46 | 3/3 | — |
| API36 consumer | 46/46 | 3/3 | — |
| API36 developer | 46/46 | 3/3 | — |
| API29 storage | — | — | 38/38 |
| API36 storage | — | — | 38/38 |

六批 `closed.json` 均记录拥有的模拟器 exit 0；没有借用其他设备。

专用 runner 复用 `run-owned-emulator.py`，拒绝已有 serial，冻结 APK 与 SHA-256，记录 owner PID，并在 finally 关闭拥有的进程。`connector-install-recovery-followup.py` 在同一独占模拟器依次执行 preparing、before-commit、after-commit 三次实际 App 进程强杀；下一次 instrumentation 断言新 PID、旧或新完整安装、凭据、选择、Skill 读取和暂存清理。

## 关键故障证据与限制

专项用例覆盖 ENOSPC 异常注入、SQLite 更新触发器强制写失败、提交响应丢失后的幂等重试、预期 revision 冲突、目标/认证变化、两包共享与独立归属、两类快照引用竞态、默认复制、fork、删除级联和重开数据库。ENOSPC 是确定性注入，没有实际填满物理设备存储。

MCP 发送测试使用真实本地 HTTP/JSON-RPC 协议往返，但显式给测试服务注入 Advanced LAN permit；不表示 consumer 产品开放了 LAN。验证连接前关闭时零连接、连接后关闭时零 tools/call，以及发送后卸载仍能结算且延后清理 Secret。待审批测试走真实 Dispatcher、broker 和 Room，批准旧卡片后断言 TOOL_DISABLED、执行次数为零、历史保留；不依赖远端账号。

前期失败已保留在 `build/hxa129/device-first`、`device-r3`、`device-r4`：分别发现 Skill 移除留下空 hash 目录导致重装失败、协议 fixture 使用了不支持的 HTTP 导入格式、端点 ID 超过64字符。生产问题已修正；协议 fixture 单独替换为显式 LAN 测试配置，不放宽导入规则。

此次不证明真实 OAuth 服务、OEM/Doze、断电耐久性或全产品设备套件。HXA-125/126 的账号边界继续单独记账；不 push、合并、发布，也不执行远端 CI。

## 冻结制品 SHA-256

同一 flavor 在两个 API 使用完全相同的 APK；以下摘要来自各批 `artifacts.json`。

| 制品 | SHA-256 |
| --- | --- |
| consumer app | `35c7ac3542e85c15c4b051b0183d6b39176ce762afb250a8ad0959db77164fb7` |
| consumer test | `d9af379c16fbc4a4166e9db5ee265331d1fdcc23739dd1a7d5f120c96ea7fe82` |
| developer app | `d27808a219d738bc071e35f1cab8b1b77ccc754fc5c2de2bd2072b0c5d5148aa` |
| developer test | `8d9b390b340b62a0ab0d147f2c2d16e09e430128475847ac7a9dfe22b6e91a1a` |
| storage test | `60cf03da8ad2e516e92cddb6417ff7854616a55a2fd803d205426cd7919fcbcd` |

最初矩阵 `matrix-r1` 在第一批正常关闭后，ADB 尚保留同一 serial 的瞬时记录，第二批被所有权门禁拒绝，未启动或接触已有设备。runner 改为每批不同端口；完整重跑 `matrix-r2` 全部通过。
