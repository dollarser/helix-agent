# Chat system 消息兼容：2026-10-09

范围：[HXA-249](../../development/tasks/HXA-249.md)。当前实际 Qwen3.8-27B / OPENAI_CHAT_COMPLETIONS，沿用应用正常 Provider/Keystore 路径。只使用合成文字和本轮新建 fixture 会话，没有导出配置、密钥、数据库或真实用户会话，没有执行模型生成的新脚本来诊断协议。

## 现场问题与等价对照

J1 原执行测试两次在 jobs.await 后第四次模型请求失败，见[J2 本轮证据](j2-auto-local-2026-10-09.md)。第一次 UNKNOWN_TOOL 来自多次搜索替换窗口；明确窗口规则后 time.now 成功，但 PROTOCOL 仍存在。由当前源码发现 await 事实增加第二条前导 SYSTEM，建立最小单变量对照。

`build/hxa248-system-probe.txt`：现有主 APK 上，单 SYSTEM 请求完成（输入64/输出17），双 SYSTEM 为 PROTOCOL，无用量回执。`build/hxa249-joined-system-probe.txt` 再次验证：

| 形状 | 完成 | 错误 | 输入 / 输出 |
| --- | --- | --- | --- |
| 原最小单 system | 是 | 无 | 64 / 23 |
| 相同指令 + 独立事实 system | 否 | PROTOCOL | 无回执 |
| 相同两段文本以空行合为一条 system | 是 | 无 | 75 / 47 |

分拆与合并仅改变前导消息分组，事实文本不删除。两次分拆失败、合并成功支持本服务多 system 兼容是实际瓶颈；这不是 token 累计额度失败的证据。原错误分类没有 HTTP 状态/响应解码细节，不能声称已知道服务端具体错误文案。探针 JUnit 1/1 代表实验成功执行，不能把其中 split=false 或原 J1 失败写成业务任务成功。

## 设计与验证

固定 OpenCode `388406238bd5ca15564a762840a2362c3a45bd9c` request.ts 默认聚合系统片段，官方 OpenAI Chat 文档提示参数支持受模型限制；来源链接与独立实现取舍见任务/Provider ADR。只在 Chat encoder 聚合连续前导 SYSTEM，同角色文本顺序、事实“不构成指令/权限”标记保留。后置 SYSTEM 不重排；工具调用/结果 ID 和原 canonical message 列表不变；其他协议不改，不新增回退、重试或权限授予。

编码回归证明聚合前导片段、保留后置片段与工具调用顺序、输入列表不变。`build/hxa249-files-final-host-r2.log`：全仓 test/detekt、双渠道 lint、APK/AndroidTest 编译通过；新增诊断/清理夹具后 `build/hxa249-final-static.log` 通过，最终探针更新另有 `build/hxa249-probe-final-static.log`。`build/hxa246-hxa249-final-source.log` 源码/文档768/任务232/ADR36/i18n/密钥检查通过；最终文档更新再刷新。

前次失败遗留的一个原 synthetic Job 经正常 USER cancel→query 终态→collect 结算，`build/hxa249-synthetic-cleanup.txt` 1/1、SYNTHETIC_ORIGINAL_SETTLED=1；严格检查 fixture 会话标题和唯一 J1_LIVE 脚本标记，不重放，也不把清理当模型收取成功。

## 安装与剩余边界

新 Developer debug APK SHA256 `ab295143d3566222215de4949095b1aa04f2537b5b116b9b316646e040e39db9`，包含已验证 J2、项目文件页修复及本次 Chat 编码修复。所有者明确要求“更新日常模拟器apk”后，`adb -s emulator-5554 install -r` 成功（`build/hxa249-approved-app-install.txt`）；API36 arm64，包 `com.helix.agent.developer`，0.0.5 / versionCode 1。安装后只读 Room master identity 为 `c34065205d0987bb3b495a2f4a663c43`，与安装前及当前生成实现一致（`build/hxa249-room-after-install.txt`）；未观察到 identity 重建，没有复制数据库、凭据、清数据、卸载或重置。

安装后沿用现有正常 Provider 绑定的 Qwen3.8-27B，未变更密钥或模型配置：

| 当前执行 | 结果 | 模型 / 工具调用 | 输入 / 输出 token |
| --- | --- | --- | --- |
| 单 / canonical 双 / 合并 system 探针 | 1/1；三个请求均完成 | 三个独立请求 | 64/40、75/30、75/68 |
| J1 原 Job start→time→await→collect | 1/1 | 5 / 5 | 48,513 / 1,097 |
| AUTO 原 Job 等待并结算 | 1/1 | 3 / 2 | 27,196 / 450 |

日志依次为 `build/hxa249-installed-system-probe.txt`、`build/hxa249-installed-live-job.txt`、`build/hxa249-installed-live-auto.txt`。真实模型测试入口 `LiveJobAwaitDeviceTest#realModelWaitsAndCollectsOneOriginalExecution` 与 `#realModelAutoWaitsAndSettlesOneOriginalExecution`，参数 liveJob=true、liveModel=Qwen3.8-27B、liveDiscoveryExplicit=true；探针额外 expectMultipleSystem=true。均只使用新 synthetic 会话和预定两秒 marker 脚本，断言原 Job 只启动一次、文件内容完全匹配及 settlementPending=false；AUTO 额外验证 waitMode=AUTO、originalCallId 匹配、executionComplete/resultAvailable=true、无额外 await/collect。清理经正常控制入口，恢复原安全 profile，仅归档本测试会话和删除其输出。

最终测试新增 AUTO 分支时发生一次 max-line-length 和一次复杂度门禁失败；通过换行及提取手动旅程断言解决，没有削弱断言。最终编译/静态检查见 `build/hxa249-auto-final-static.log`，源码门禁见 `build/hxa249-final-source.log`。上述真实旅程分别为一次通过，不证明普遍成本收益或多轮稳定率；其他服务/模型、Consumer 真实模型、长稳、完整取消/恢复组合及未连接真机仍开放。
