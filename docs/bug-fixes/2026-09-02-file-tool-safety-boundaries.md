# Bug Fix: File-tool safety boundaries are executable

Status: fixed
Date: 2026-09-02
Related HXA: HXA-040, HXA-041, HXA-042, HXA-043, HXA-044
Affected modules: `core:workspace`, `tools:files`, `tools:framework`, `app`

## Problem

HXA-040～044 后的对抗性审查发现四类契约只有部分落位：部分路径解析/IOException 会把真实路径带入 model-visible 错误；ToolDescriptor timeout 没有通用执行方；原子写在 rename 前 fsync 目录，没有持久化最终 target 目录项；copy/move/edit/write 的内存工作集缺少与操作语义一致的硬上限。

## Impact

错误路径可向模型泄漏主机目录；一个不返回的 executor 可以绕过 descriptor 的时间预算；掉电语义与“原子发布”声明不一致；大文件可导致手机进程内存压力或 OOM。这些问题不要求模型提供恶意参数才可触发，普通 I/O 竞态、阻塞 executor 或大文件即可触发。

## Root cause

文档和 descriptor 定义了 sanitized error、timeout、atomic publish 和资源上限，但没有在每条真实执行路径上指定唯一实施者。Quota 被误当作内存上限，文件 fsync 被误当作目录项持久化，而 catch-all 的 raw exception message 被误当作可安全显示的错误摘要。

## Fix and invariants

所有文件工具的 IOException/路径解析失败映射为稳定、无真实路径的错误；Dispatcher catch-all 只允许固定消息或异常类名进入 model-visible detail。`ToolDispatcher` 是 descriptor timeout 的唯一通用执行者：executor 超过 deadline 后只结算一次稳定 TIMEOUT，阻塞线程只做 best-effort interrupt 并被放弃，不盲目重放。原子写使用“file data fsync → atomic rename → target 目录 best-effort fsync”；rename 后目录 fsync 失败不得返回“未执行”的假失败。copy/跨 scope move 使用 64 KiB 分块和增量 SHA-256，edit 在整文件解码前以 50 MiB 硬限与紧邻复检收窄竞态窗口，write 在 schema 和 executor 两层限制 4 MiB UTF-16 字符。

## Alternatives considered

**依赖 UI/模型不提供大输入。** 放弃，因为模型不是可信的资源边界，executor 必须防御性复检。

**只在每个工具 executor 内实现 timeout。** 放弃，因为新工具可能遗漏实现，且 Dispatcher 无法统一结算、审计和放弃语义。

**rename 前 fsync 目录即认为原子持久。** 放弃，因为该 fsync 没有覆盖尚未发生的 target 目录项变更。

**rename 后目录 fsync 失败就整体报失败。** 放弃，因为 target 已经发布，返回失败会诱导调用方重放并可能重复副作用。

## Regression verification

- 路径和 IOException 测试逐工具断言 model-visible 错误不含真实路径；Dispatcher catch-all 覆盖 raw message 清洗。
- Dispatcher 测试以 30 秒阻塞 executor 验证 400 ms 超时、恰一结算，并验证 deadline 前异常保持普通失败语义。
- `AtomicFileWriter`/stream 测试覆盖发布顺序、中途 abandon、temp 清理和目录 fsync 降级。
- 多 chunk copy/move 测试断言字节、全文件 hash、源文件和 temp；edit/write 测试覆盖硬上限、严格 UTF-8、NUL 与后段非法字节。
- 已记录回归命令：`./gradlew :core:workspace:test :tools:framework:test :tools:files:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`。

## Residual risk

超时后不可中断 I/O 可留下计入 scope quota 的 `.helix-tmp-*` 孤儿；现有 `reclaimTempFiles` 没有 age 阈值，直接接线会误删并发活写，因此 age-based reclaim 属 HXA-046 同批后续设计。Trash entry 转义后超过 `NAME_MAX` 时仍会 fail closed 且保留原文件，长路径命名策略也属 HXA-046。

## Related records

- [M4 文件工具对抗性审查](../history/documentation-review.md#17-m4-文件工具对抗性审查与复审2026-09-02)
- [HXA-041 原子文件操作](../completion-records/HXA-041.md)
- [HXA-042 基础文件工具](../completion-records/HXA-042.md)
- [HXA-043 copy/move/delete](../completion-records/HXA-043.md)
- [HXA-044 SAF adapter](../completion-records/HXA-044.md)
