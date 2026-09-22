# Bug Fix: 授权、页面与 Runtime 修复及分支收敛

Status: fixed
Date: 2026-09-18
Related HXA: HXA-209, HXA-190, HXA-196
Affected modules: app, core/storage, core/workspace, feature/browser, provider/api, runtime/cli-app, runtime/cli-client

## Problem

日期：2026-09-18。起点 main `3f5039e4`；问题取舍见[审查复核](../evidence/development/review-followup-2026-09-18.md)。本记录区分修复、核心平台切片与整体验收，不把历史证据迁移成新制品通过。

## Impact

默认权限变更可能作用于旧会话，权限部分保存与快速编辑可能造成状态/审计不一致；来源移除和标签超限可触发页面异常。订阅长流保留多份累计结果，失败发布与清理存在准入占用或证据丢失风险。此轮未制造设备 OOM，不将源码风险写成已观测崩溃。

## Root cause

会话缺少创建时快照、编辑缺少统一事务和最新值合并；UI 未处理底层拒绝及失效来源；Runtime 将流式预览与完整结果都保存在累计内存集合，持久写入与结算失败路径未覆盖完整生命周期。

## Fix and invariants

| 范围 | 根因与修复 | 验证重点 |
| --- | --- | --- |
| 会话权限 | 无显式配置的旧会话会跟随新默认；创建会话与权限快照置于同一事务，Room v22→v23 给既有缺行会话保存升级时实际生效的默认，保留已有显式选择；恢复默认也保存快照 | 新旧会话、迁移/重启、后续默认改变不影响旧会话；无法还原历史未保存的默认选择，不声称重建历史 |
| 编辑与审计 | 配置、草稿、审计分开写，快速编辑可能覆盖；生产编辑服务使用 Room 事务，CUSTOM 单条规则基于事务内最新草稿修改，UI 串行操作并显示失败 | 注入审计失败回滚；并发不同规则不丢更新；取消不伪装成保存失败 |
| 文件与浏览器 | 删除正在浏览的 SAF 来源留下失效状态；标签上限异常未在按钮边界消化 | 来源移除后回 Workspace，清理选择与弹窗；浏览器达到上限不抛出 UI 异常 |
| 目录与 Provider | 目录截断信息丢失，枚举先全量排序；模型列表正文读失败漏出异常 | 保留截断标记并显示；有界堆保存排序前缀；IO/超时映射为已有 Provider 错误 |
| 订阅预览 | 预览累计保留事件、按条数限制不能限制单批字节；客户端重复保留已展示事件 | 文件游标预览与 128 KiB 单批上限；巨大事件停止预览前缀，由终态完整结果补齐；客户端常量空间哈希链验证已展示前缀 |
| Codex 结果流 | 流式接收与后续名称映射保留多份事件集合 | 生产 Codex 路径事件与索引落临时文件，按项转换/编码，任务结束或 Runtime 重建清理临时文件 |
| 失败结算 | 请求写入后记录失败留下孤儿目录；输出失败可占住 active；reconcile 回调异常未覆盖 | 只清理可证明未提交的文件；结果写失败持久 FAILED 并释放准入；整个 reconcile 写入路径捕获并记录失败，重试不执行模型 |
| Secret | rename 失败后普通覆盖复制破坏原子性 | fsync、0600 临时文件与同目录 POSIX rename；失败保留旧目标，finally 清理临时文件 |

ACK 表示调用方已消费结果，持久 ACK 后清理 payload；清理预检失败不写 ACK，实际删除失败向调用方报错并由重复结算/重启继续清理。没有把部分删除后的最后结果当成可重新读取的成功结果，也不靠重新执行模型补结果。

**资源边界**：未恢复已撤销的累计字节/总时长配额。此次减少流传输期间的累计内存和重复副本，但终态协议仍生成完整 ByteArray，客户端终态仍解码完整结果 List；其他订阅 Provider 尚未统一改为该事件文件实现。没有做设备 OOM/长稳/吞吐基准，不能宣称端到端恒定内存或所有资源问题已消失。

## Alternatives considered

不采用删除配置恢复动态默认，不以禁用按钮替代持久事务，不取消结果哈希验证，不恢复 ADR 已撤销的累计响应配额，也不将全仓库重构作为修复前置。Secret 不再使用普通复制覆盖作为 rename 失败回退。

## 本地合并

- 修复提交 `1b095083`。
- 196 分支 `63829af2` 以 `32de8e8d` 合入 main：包含平台与核心，以及启动前取消证明补修。
- CI 分支 `9650a9a4` 以 `bfce6fa8` 合入 main：并行分析/测试构建门禁、失败诊断与 Linux rg 安装。唯一冲突为 status，保留两侧有效结论并更新执行范围。
- batch B、193/195、搜索及 Root 分支提交原本已在 main，不重复合并。`v0.0.1` 为历史快照，不合入当前实现。保留原审查报告及 Claude 工作树未提交脚本。

以上是本地合并，未 push；旧 main 或 PR 的远端 CI 绿色不等于本次合并提交的远端验证。

## Regression verification

修复前和修复后均执行 `./scripts/check-all.sh --all`。修复后 `build/review-full-gates2.log` exit 0，涵盖源检查、格式、静态检查、主机测试、lint、Debug/Release 构建、锁及 variant/Runtime 边界。

授权修复阶段，API29/36 × consumer/developer 四次独占运行各 15 项通过（共 60），包括两阶段真实进程死亡恢复；分别为 `build/review-a-final-consumer-29`、`review-a-final-consumer-36b`、`review-a-final-developer-29`、`review-a-final-developer-36`。这是该阶段的 APK 证据，后续额外 SAF/Secret 测试及 196 整合需要新制品验证。

Runtime 修复后、196 合并前执行：

```sh
python3 scripts/verify-integrated-runtimes.py --avd HelixApkUpgrade_API29_20260918 --port 5670 --memory-mb 4096 --cores 4 --output build/review-runtime-final-29
python3 scripts/verify-integrated-runtimes.py --avd HelixApkUpgrade_API36_20260918 --port 5672 --memory-mb 4096 --cores 4 --output build/review-runtime-final-36
```

两次 exit 0，各 35/35（36.921 / 70.333 秒），owned 进程退出记录为 0；使用本地 fixture，不证明真实付费账号通过。

### 合并后的验证入口

`./scripts/check-all.sh --all` 在合并后的 main 再次 exit 0（`build/review-merged-gates.log`）；`python3 scripts/verify-integrated-runtime-apks.py --build-type release` exit 0，consumer/developer 的 Release 边界均通过，196 调试探针没有进入 Release。CI 分片契约 `python3 scripts/debug/2026-09-18/verify-ci-gates.py` 及 shell 语法检查通过。

SAF 测试定位修正后，最终 `./scripts/check-all.sh --all` 再次 exit 0（`build/review-final-gates.log`），包括最新测试源码的 lint/编译检查；不是只沿用修正前的主机绿灯。

设备脚本 `run-review-merged-device.py` 对 consumer 运行 16 项修复回归及 47 项存储/Secret 测试，对 developer 运行 16 项修复 + 35 项原 Runtime + 7 项 detached 行为 + 1 项死亡探针准备；准备测试之后必须另验主进程单独死亡，不能只看 instrumentation 绿灯。所有组均先完成权限恢复的 setup/verify 两阶段，owned runner 记录进程与 APK 身份。

首次合并矩阵 consumer/API29 为 15/16，新增 SAF 旅程点击了滚动菜单外的入口；修正为 `performScrollTo()` 后用新测试 APK 重跑。保留 `build/review-merged-consumer29` 失败证据，不把该轮记为通过。

```sh
python3 scripts/debug/2026-09-18/run-review-merged-device.py consumer 29 5676 build/review-merged-consumer29-r2
python3 scripts/debug/2026-09-18/run-review-merged-device.py consumer 36 5678 build/review-merged-consumer36
python3 scripts/debug/2026-09-18/run-review-merged-device.py developer 29 5680 build/review-merged-developer29
python3 scripts/debug/2026-09-18/run-review-merged-device.py developer 36 5682 build/review-merged-developer36
```

四次均 exit 0，`closed.json` 均 exit 0；`python3 scripts/debug/2026-09-18/summarize-review-matrix.py` 再核对数量、退出、死亡证据与同 flavor 制品一致性，生成 `build/review-merged-matrix-summary.json`。

| flavor / API | app instrumentation | storage instrumentation | 独立主进程死亡检查 |
| --- | --- | --- | --- |
| consumer / 29 | 16/16 | 47/47 | 不适用 |
| consumer / 36 | 16/16 | 47/47 | 不适用 |
| developer / 29 | 59/59 | 不重复执行 | 通过，原 Runtime PID 不变，原 Job SUCCEEDED |
| developer / 36 | 59/59 | 不重复执行 | 通过，原 Runtime PID 不变，原 Job SUCCEEDED |

合计 244 项 instrumentation，其中 2 项仅为死亡探针准备，另有 2 次宿主独立死亡检查；不是 244 项新增测试，也不是全产品套件。最后现场 `adb devices -l` 无设备，未借用他人模拟器。

同 flavor 两 API APK SHA-256 完全相同：

- consumer app：`12c8d44af2d56f83e26c10c61a6c4de50ac4093e6a3a569781add7b0f2a2b500`；test：`6431b6f3b13a220190193969c32b463e61a963804a2c6faa4ce77f7da02ff7b9`。
- developer app：`372363cb0e8ba53674ccc25e87188ae2755c6f112ebe17bd118b004dfb2e5865`；test：`072ee0fd0418fde5fda052f7cccdf152d5063a14e289876d5fdac818034eda40`。

过程失败保留：首次迁移设备用例因 app 测试 APK 未含 v22 schema 失败，补 fixture 后通过；预览测试使用超过 ModelEvent 构造限制的数据，改用合法 Unicode 大事件覆盖字节边界；输出失败 fixture 改为确实不可覆盖的非空目录。一次并发 Gradle 导致 KSP 缓存冲突，改为串行后完整门禁通过；未删除或跳过失败测试。

## Residual risk

196 平台/核心进入 main 不代表产品完成：生产异步工具注册、会话授权/预算适配、任务投影及结果导入/重查仍待独立接线；真机 HOME/锁屏/Doze 缺当前设备证据，HXA-196 保持未完成。同步 `code.linux.run` 语义没有改成异步 accepted。详见[196 核心记录](../evidence/development/hxa-196-platform-plan-2026-09-18.md)。

2026-09-18 本轮对 207/191/206 仅写计划；三项后来已完成，归属见[已完成交接汇总](../evidence/development/completed-handoffs-2026-09-22.md)。本记录不再作为待开发指令。真实账号、OEM 长稳和发行继续独立记账。

## Related records

[审查复核](../evidence/development/review-followup-2026-09-18.md)、[196 核心实现](../evidence/development/hxa-196-platform-plan-2026-09-18.md)、[已完成交接汇总](../evidence/development/completed-handoffs-2026-09-22.md)。
