# Bug Fix: 历史正确性审查收口

Status: fixed
Date: 2026-09-30
Related HXA: HXA-231, HXA-232
Affected modules: app, core/storage, tools/framework

## Problem

所有者要求逐项核对历史分析并修复仍存在的问题。原报告基于 `v0.0.3 / 53beeedf`；本轮在本地 `main / 7480141b` 加既有未提交工作上核对和修改，保留 HXA-232 等已有改动。未提交、未推送；本轮模拟器、真机、真实模型与账号均 **not requested**。之前 HXA-232 API36 证据不覆盖本轮修改。

| 原项 | 当前核对与处理 |
| --- | --- |
| B1 Provider 旧探测写回 | 确认存在，已修复。探测绑定配置快照和本次服务内 generation；配置修改、凭据更新、删除、窗口设置及手动能力声明使旧探测失效。目录、窗口、能力和连接状态在同一发布锁内校验后写入；旧成功或失败返回 `PROBE_SUPERSEDED`，不覆盖当前状态。 |
| B2 压缩推理参数 | 确认存在，已修复。压缩和普通请求共用实际 `providerId + model` 的选项解析；仅在该模型支持 LOW 时使用 LOW，否则 OFF。默认模型能力不证明其他模型能力。 |
| B3 Git 区域混淆 | 确认存在，已修复。选择、加载和读取传递 `path + area`；暂存区比较 HEAD/index，工作区比较 index/worktree，取消隐式回退。 |
| B4 unborn HEAD | 确认存在，已修复。首次提交前使用空树读取暂存新增文件；保留暂存后继续修改的两个区域。 |
| B5 Git 错误等同空内容 | 确认存在，已修复。加载、文本、无差异和读取错误分别展示；缺失对象/仓库不会显示成“没有差异”。错误文案不泄露异常正文。 |
| 报告 R1 内容发布与清理 | 确认存在竞争窗口，已加协调。以 canonical content root 共享进程内锁，覆盖内容创建/复用至引用提交，以及无引用检查至删除；GC 和永久删除会话使用同一协调。工具结果物化也纳入事务外层的发布锁。宽限期仅保留作回收保留策略。 |
| 报告 R2 原子工具绑定 | 仍未完成。按所有者先前决定保留 HXA-231 R1 为下一实施任务；本轮不把资源上限修复或已有 contractHash 当作原子绑定完成。 |
| 报告 R3 真实执行资源 | 确认条件性资源风险，已修复无限扩张路径。普通执行最多 32、可信 control 执行独立最多 4；均无等待队列。饱和明确返回未提交、无副作用失败，记录实际运行及取消/超时后仍运行的数量。原 effect ownership 继续由真实 executor 退出释放。 |

注意两个 R1 不同：报告的 R1 是内容清理竞争；HXA-231 的 R1 是原子工具绑定。

## Impact

旧探测可误标当前配置；错误推理参数可使压缩失败；差异可能显示另一区域或漏报；并发清理可能使持久引用缺失正文；不响应中断的工具可能无限占用线程。

## Root cause

异步探测缺少发布身份校验，压缩绕过实际模型解析；Git 丢失区域且把无 HEAD/异常统一降为空；文件发布与数据库引用不共享清理协调；cached pool 把逻辑结束与真实执行退出分离却没有容量边界。

## Fix and invariants

- Provider 网络探测、窗口发现及配置读取保留调用方 Job；已取消的调用即使网络非协作返回，也不能进入发布。通过校验后的短本地发布使用 NonCancellable 完成多存储更新，避免普通取消切断收尾；这不等于跨存储的进程崩溃原子事务。
- Git 暂存区与工作区共用每侧 1 MiB、总计 50,000 行、2,000,000 次比较上限和 64 Ki 字符预览。过大、二进制、计算超限、截断明确提示；这里限制计算工作量，不承诺任意设备上的固定毫秒完成时间。
- 执行饱和不是 Goal 生命周期总调用次数限制；32/4 限制同时仍存活的执行线程。不能把 Future 取消、超时状态或线程计数当成外部副作用已停止的证明。control 资格来自可信具体 executor 包装，不来自模型参数、名称或扩展 metadata。

## Alternatives considered

不只取消旧探测协程、不延长 GC 宽限期代替协调、不用无限队列替换 cached pool、不提前释放未知副作用 owner；不把 Git 读取失败伪装成空差异。继续现有模块、执行路径与授权规则，原子绑定另按已定 HXA 收口。

## Regression verification

### 确定性回归

- `ProviderProbeGateTest`：旧成功晚于编辑、旧失败晚于新成功、删除/同配置重建、调用取消及取消后无挂起返回；使用 coroutine barrier 固定交错。
- `ProviderRowUiModelDiscoveryTest` / `SupportedReasoningTest`：默认模型支持推理、当前模型不支持或仅支持 HIGH、未知模型，以及摘要 LOW 和普通请求偏好的共同解析。
- `GitAreaRegressionTest`：同路径 HEAD/index/worktree 三份内容、unborn HEAD、损坏对象和缺失仓库、暂存大文件与二进制。
- `ContentPublicationRaceTest`：GC 已检查后发布同 hash、复用旧文件至引用提交、canonical 等价根协调和孤立文件仍可回收。使用真实文件与受控 reference checker；不是 Android Room 进程骤停验收。
- `ToolExecutionCapacityTest`：不响应中断的 executor 在取消和超时后仍占容量，连续饱和不增线程、不排队；独立 control 可执行，实际退出后计数归零。

### 主机验证

通过共享 host slot 执行以下完整门禁（包含 UP-TO-DATE，不声称全部强制重跑）：

```sh
./gradlew spotlessApply detekt test \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

最终 `BUILD SUCCESSFUL`，989 tasks（51 executed / 938 up-to-date）。本轮生成的关键模块 JUnit XML：

| 模块 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| core/storage debug | 219 | 0 | 0 |
| tools/framework | 208 | 0 | 0 |
| app Consumer debug | 955 | 0 | 4 |
| app Developer debug | 1003 | 0 | 4 |

每渠道 4 项跳过是原有外部账号/本地归档 fixture 未提供，没有删除或弱化测试。新增/扩展的 Provider、Git、内容交错与执行容量用例均无跳过。核心定向套件本轮执行，最终重复门禁部分任务复用输出；原始日志位于忽略的 `build/historical-fixes-*.log`。

追加 `./gradlew -PincludeSpikes=true test spotlessCheck` 通过：508 tasks（4 executed / 504 up-to-date），保留实验模块而不冒充重新执行全部测试。`check-all.sh --source` 通过文档、ADR、国际化、秘密扫描及脚本测试；最终 `spotlessCheck`、tracked/staged `git diff --check` 均通过。未执行 release 构建、设备安装或仪器测试。

中间失败保留：Git 新回归发现 JGit cached blob 的 `getBytes(limit)` 不替代 size 检查，以及损坏对象会抛 `JGitInternalException`；均已修正。静态检查发现新函数规模/嵌套问题，已整理。首次全量 JVM 执行中，已有 `BrowserDownloaderTest.aRedirectToANonHttpSchemeIsRefused` 返回 `http-404` 而非 `redirect`；未改其断言，强制独立重跑 11/11 通过，当前未证明根因，不声称已修复这一偶发边界。

## Residual risk

1. HXA-231 R1：descriptor/executor 单一原子绑定，以及来源替换、审批等待撤销、旧调用不跳到新实现的交错验收。
2. 工具发现：当前 AND 子串匹配、名称排序、零命中清空窗口、MCP 16/17 曝光切换仍存在。后续按[工具专题](../research/topics/tool-exposure-and-discovery-2026-09-29.md)处理召回/连续性及最终 schema 成本，不在同轮混入排序和插件生命周期重构。
3. ChatService 职责、插件身份/连接/安装生命周期属于后续结构工作；文件长不是功能失败证据，不新造第二个 Engine。
4. 本轮 Provider 回归主要覆盖发布协调器与真实配置行派生，未完成实际网络请求 + Android Room + UI 的交错设备验收；内容协调仅覆盖当前单应用进程内内容拥有者，不承诺跨进程文件锁。进程崩溃、OEM 和真实模型恢复质量仍需对应证据。
5. 开发期删库、共享 UID、真实账号/OEM/16 KiB 等保持原接受边界。历史输出截断、额外调用及探测偶发失败不能因此宣布全部解决。

## Related records

- [当前状态](../development/status.md)
- [HXA-231](../completion-records/HXA-231.md)
- [Provider ADR](../adr/provider/001-models-and-connection.md)
- [Git ADR](../adr/workspace/003-git-boundaries.md)
- [执行与审计 ADR](../adr/permissions/003-dispatch-and-audit.md)
