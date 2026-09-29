# ADR-RUNTIME-003: QuickJS 隔离执行底座

Status: accepted
Date: 2026-09-16
HXA: HXA-050
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

端上 JavaScript 需要可取消、有资源限制且不继承应用权限的执行域。

## Decision

采用 Cash App Zipline 的 QuickJs 作为解释执行底座，确切版本由依赖清单与锁文件维护，升级必须验证 native/ABI、许可证和执行约束。

- 默认采用 Android non-exported isolated UID 进程，不注册特权宿主桥；Zipline 本身不是沙箱。用户可以明确开启独立的“原生访问”总开关，允许脚本在非导出、非 UI 的应用 UID 私有进程调用文件、网络和 Java/Android API。任意反射能力不提供三类独立权限隔离，UI 不作此承诺。
- 原生总开关默认关闭，模型参数不能授予该权限；现有工具审批和会话策略仍在执行之前生效。执行客户端在绑定前检查用户设置，并在等待中检查授权修订；撤销后旧执行不可因重新打开而恢复。原生进程最终回收，已发生的副作用仍如实保留，不能标记为回滚。
- 原生执行与主应用共享 UID，能够访问应用私有数据；总开关不是对恶意原生脚本的凭据隔离边界，只适用于用户授权运行的可信脚本。Android 系统权限仍有效，不提供 root 或隐藏 API 绕过。
- 每次执行创建全新 QuickJs，在专用执行线程上创建、使用和 close，设置 memoryLimit、InterruptHandler 与栈限制。native 线程栈必须满足引擎要求，设备验证确定参数，不能在任意线程初始化后跨线程执行。
- 输入采用严格封装与常量数据，不拼接未转义代码；当前引擎的动态编译禁用行为必须有攻击测试，依赖升级不得默默打开 eval 等路径。
- 取消、预算超限、异常、进程死亡均有明确结算，输出有界且不可信。Code Mode 的文件/网络回调不是现有隔离解释器的隐含能力。

## Alternatives considered

自写 JNI/解释器增加 native 维护；主进程执行或给隔离脚本常驻特权桥破坏边界。不为宿主 Code Mode 预先开放文件/网络回调。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-09-30：所有者接受原生总开关方案；默认隔离路径保留，新增用户显式原生授权，不声称网络/文件/Android 三类独立隔离。设备验证推迟至 QuickJS 完成后另行授权，主机验证不代表设备通过。
