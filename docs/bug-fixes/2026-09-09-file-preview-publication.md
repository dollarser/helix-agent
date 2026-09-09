# Bug Fix: 文件预览结果的发布与观察边界

Status: fixed
Date: 2026-09-09
Related HXA: HXA-180
Affected modules: app 文件页

## Problem

API36 的 `FilesScreenTest.previewsTextFileWithHashInfo` 在完整套件和独立进程中反复超时。界面语义树确认：文件列表包含正确的 `note.txt` 和 11 B 大小，预览弹窗却停在“无预览”，没有正文或元信息。API29 同一流程通过。

## Impact

用户可能看到已经存在的文本文件没有预览。旧界面还把读取中、读取失败和真正不支持预览混为同一状态；读取异常缺少恢复展示。

## Root cause

应用层将文本、图片和元信息分为三个共享可变字段，依赖 effect 的恢复上下文完成 UI 发布，弹窗只在嵌套 slot 中观察这些字段。诊断记录显示：读取成功，但发布日志落在 IO 线程；增加弹窗调用层的观察日志后重组行为改变，测试通过。原实现没有明确的结果发布和弹窗观察边界。

这不是文件内容缺失。现有证据也不足以把底层原因归咎于某个 Compose 或 Android 系统版本；修复落实应用自身的状态契约。

## Fix and invariants

- 用单一不可变 `FilePreviewState` 表达 Loading、Ready（正文/图片/元信息）和 Failed，不再发布互相脱节的字段。
- 文件读取仍在 IO dispatcher；结果显式进入 Main.immediate 发布，弹窗在调用层观察并捕获该状态，再交给内容 slot。
- 发布前核对当前 scope/path；关闭、切换或取消不接收过期结果。
- 读取异常展示失败，取消继续传播；文件修复后重新打开可以恢复。真正不支持预览仍展示可读取的元信息。
- 导出信息从同一 Ready 状态派生，不保留第二份元信息状态。诊断日志已经从产品代码移除。

## Alternatives considered

增加超时时间不能修复长期停在初始态；把观察日志留在产品中会依赖日志副作用；仅把三次 setter 挪到同一代码块也没有建立单一状态和明确 dispatcher，均未作为最终方案。

## Regression verification

- 修复前失败证据：`build/debug/2026-09-09/hxa180-preview-{1,evidence,deep}`，包括非空失败 JUnit、深层语义树和日志。带观察日志的诊断轮 `hxa180-preview-trace` 记录读取与发布线程。
- 修复后：`hxa180-preview-verified-{1,2,3}` 各 1/1，三轮分别启动独立 API36 实例；没有调大测试超时。
- 最终 `hxa180-files29-verified` / `hxa180-files36-verified` 各 18/18，包括原预览、长文本、二进制元信息、读取失败后修复重开及完整文件操作。
- 命令和主机证据见 [HXA-180](../completion-records/HXA-180.md)。

## Residual risk

没有进行本轮物理手机或长稳复验；不宣称修复底层 Compose、JNI 或 Binder。不同 SAF Provider 的可用能力仍由实时权限和操作结果决定。

## Related records

- [HXA-180](../completion-records/HXA-180.md)
- [文件管理决定](../adr/0041-manual-file-management-mutations.md)
