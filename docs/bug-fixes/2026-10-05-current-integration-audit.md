# Bug Fix: 当前整合审查：工具说明、屏幕外反馈与评测可信度

Date: 2026-10-05
Status: fixed
Related HXA: HXA-231、HXA-244；当前所有者要求整体审查、修复明确问题并优化文档

## Problem

重点复核最近整合的文件工具模型投影、Mobile Use 节点与错误反馈、当前模型评测/清理、共享原生 DNS 和插件入口；结合主机回归核对权限、订阅、MCP/A2A 的相关接线。保留原有未提交工作，没有提交、推送或清空设备数据。

本次没有新的设备或真实模型授权，未连接或操作模拟器、未消耗模型额度。以下修复完成主机验证，不能直接继承上一轮模型实测的 5/5。审查未声称整个仓库不存在其他缺陷。

## Impact

文件工具描述越界会阻断模型请求；不完整的点击反馈增加无效尝试。评测驱动误判和配置恢复竞态则会降低验收可信度，并可能遗留测试配置。

## Root cause

会话投影重复维护工具用途；点击匹配没有复用屏幕外目标判断；旧驱动把进程退出与测试成功混同，缺少结果身份校验及超时后的设备端停止。

## Fix and invariants

| 问题 | 影响与修复 | 验证 |
| --- | --- | --- |
| 文件工具用途仍由会话层重写 | `read/write` 加后缀会使合法的 1024 字符描述再次越界；其他文件工具的固定替代文案也可能丢掉执行前提。删除用途重写，完整保留原始注册描述；相对路径帮助仍放参数 schema，expectedSha256 使用规则原本就在参数中 | 全部 13 个文件工具投影在描述上限处保持原文和 required；实际 read/write 描述保持一致；双渠道 App 单测通过 |
| 原子点击没有解释屏幕外目标 | `ui.find` 已提示滚动，但 `ui.click_match` 仍报 TARGET_NOT_CLICKABLE。现在明确返回 TARGET_OFFSCREEN 和先滚动再观察的恢复提示 | 回归验证不派发节点动作、sideEffectFree=true、无 UNKNOWN/review |
| 实测脚本可假通过或覆盖证据 | instrumentation 进程可退出 0 而测试失败；旧脚本没有失败退出码，也可能在同名重跑时读取旧结果。现在使用仓库共用 instrumentation 状态解析器，核对精确测试身份、终态和独立评分，失败非零退出；拒绝复用 trial，执行前移除该次唯一远端结果 | Python 主机 mock：假绿色、跳过、错误测试身份、旧结果、false 评分、非 COMPLETED、oracle 错误全部拒绝 |
| 评测异常退出恢复存在竞态 | adb 客户端超时不代表设备内 instrumentation 已结束。现在异常时先停止被测包，再恢复配置；每个恢复步骤分别尝试，失败显式报告；只恢复测试持有的配置键 | Python mock 验证超时终止先于恢复，无原配置时移除测试键、其他偏好保留、坏文档拒绝覆盖 |
| 普通设备套件会误跑真实模型夹具的失败断言 | 未提供 opt-in 时旧代码直接 check 失败。现在缺失/false 明确 skip；非法 opt-in 值或显式开启但缺 Provider/model 仍失败，不静默跳过坏输入 | AndroidTest APK 编译、静态检查；本次未执行设备 skip 路径 |
| 旧证据缺少字段被汇总为 0 | 未采集的 token 或 tool resultStatus 不应解释为“零成本/零失败”。汇总脚本缺字段输出 null，保留已知指标 | 重新读取旧的本地 JSON，baseline 缺失项为 null，最终实测既有指标不变；未重跑模型 |

## 文档收敛

- `status.md` 从阶段流水记录收敛为当前整合表、已有能力、开放验收、下一步和限制。保留原 HXA 顺序与证据入口，去掉“高权限仍统一依赖普通无障碍”等已过时限制；Project Memory 表述为已交付接线、设备验收待补，不再列成尚未开始。
- PackageInstaller 调研明确是逐阶段历史；早期安装失败、后续成功安装与最新合成评测分别指向对应证据。多轮追加提示不包装成一次请求无干预成功。
- 竞品差距文档补入局部模型实测，消除“设备完全未验证”的旧表述；保留真实 App、多变体、OEM、视觉和成本的未闭合边界，不给竞品排名或完成百分比。
- 先前动态工具描述修复记录补上本次长度边界回归，实测报告补充新驱动的显式准入、完整成功判定及主机验证边界。

## Alternatives considered

不通过提高描述上限、截断工具说明或放宽点击验证掩盖问题。评测复用现有 instrumentation 解析器，不以退出码或旧文件代替测试终态，也不把缺失指标当作零。

## Regression verification

```text
./gradlew :app:testDeveloperDebugUnitTest :app:testConsumerDebugUnitTest \
  :extensions:mobile-use:testDebugUnitTest :tools:files:test :core:policy:test \
  :extensions:plugin:test :extensions:mcp:test :extensions:a2a:test \
  :runtime:cli-app:testDebugUnitTest :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:assembleDeveloperDebugAndroidTest detekt spotlessCheck
python3 scripts/debug/2026-10-05/test-capability-runner.py
bash scripts/check-docs.sh
```

主机整合日志 `build/current-audit/host-check-final.log` 通过，报告包含 3198 个测试实例：3190 通过，8 条条件性跳过，0 失败/错误。两个渠道重复实例不算独立不同场景；跳过为本地 Connector/WorkBuddy 样本和 opt-in 外部验收缺少输入，不计通过。部分未变化模块使用 Gradle up-to-date 结果，不冒称本次全部重新执行。Python 驱动 11 项主机 mock 通过，没有访问 ADB 或真实服务。

首次主机编译发现新增测试的 ToolName 导入命名空间错误，修复后完整命令重新通过；未修改生产逻辑或放宽断言来消除失败。随后进一步区分非法 opt-in 与正常 skip，设备测试 APK 和静态检查复验日志为 `final-gates.log`。测试计数见 `test-counts.txt`，文档检查见 `docs-check.log`；均在忽略目录 `build/current-audit/`。

## Residual risk

1. **后端能力差异与失败恢复**：系统返回等动作仍有普通无障碍依赖；Root/Shizuku 就绪不代表所有 ui.* 都可用。模型遇到不可用能力后的额外调用仍需真实轨迹评估，不能靠假成功或无边界回退解决。
2. **代表性与成本**：五项合成任务不足以证明真实 App 完成率；重复观察、目录检查和长上下文开销仍需多变体、重复测量与缓存计费数据。
3. **验证条件**：本次新增修复尚未设备复测；API29/34、Root、普通无障碍单后端及真实 OEM 不能由已有 API36 Shizuku 样本代替。
4. **发行与数据升级**：debug 安装不等于正式签名、数据库升级或商店验收；原 HXA-122 边界不变。

这些范围留在[当前状态](../development/status.md)和各任务中，不另建一套重复待办，也不把未验证项当成已证实缺陷。

## Related records

- [动态工具描述问题](2026-10-05-discovered-tool-description.md)
- [当前模型局部实测](../evidence/development/current-model-capability-eval-2026-10-05.md)
- [当前状态](../development/status.md)
