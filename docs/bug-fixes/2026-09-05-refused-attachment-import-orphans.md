# Bug Fix: 不支持附件被拒绝后遗留未注册私有副本

Status: fixed
Date: 2026-09-05
Related HXA: HXA-049, HXA-056
Affected modules: `app`（`ChatService`、附件设备 E2E）

## Problem

附件导入必须先把 `content://` 字节复制到 App 私有目录，再根据可信字节分类。此前分类为
PDF、DOC、音频、UTF-16 或其他不支持类型时，产品正确返回
`UNSUPPORTED_ATTACHMENT_TYPE`，也没有把附件加入消息或发送给 Provider，但这份尚未
注册的私有副本及其 attachment-id 目录没有被删除。

## Impact

- 重复尝试不支持附件会在 Workspace 中积累用户不可见的 orphan 文件并占用配额。
- UI、数据库和 Provider wire 均表现为“已拒绝”，容易让只检查功能结果的测试产生假绿。
- 不能通过在复制前信任 Provider MIME、文件名或扩展名来规避残留，否则会削弱既有的
  字节级类型确认边界。

## Root cause

分类发生在一次性私有复制之后，这是为了让分类器读取稳定、受控的真实字节；但拒绝分支
只更新了 UI，没有对尚未进入 Artifact/消息绑定生命周期的临时副本承担清理责任。常规
CASCADE 或 Artifact 删除路径不会看到这份未注册文件。

## Fix and invariants

- 不支持类型分支从不透明 model reference 重新解析受控 `FileScopePath`，再通过既有
  staging resolver 定位文件；解析或解析范围失败时保持 fail closed，不接受任意路径。
- 删除 payload 后只尝试删除该次导入唯一的、应为空的 attachment-id 父目录；出现意外
  内容时目录删除失败是安全结果，不递归删除。
- 不支持附件的闭合结果必须同时满足：稳定拒绝、0 staged attachment、0 Artifact/消息
  绑定、0 Provider wire call、0 imported payload、0 空 orphan 目录。
- 清理失败不能把拒绝改成成功，也不能解析、渲染、OCR、转码或上传不支持内容。

## Alternatives considered

- 在复制前按 MIME 或扩展名拒绝：Provider 元数据不可信，会降低 ADR-0014 的字节确认
  要求，未采用。
- 让定期配额清理回收 orphan：延后且不确定，正常拒绝路径应当同步承担自己创建的副本。
- 递归删除 attachment 根目录：目标范围过大，异常布局下可能删除其他导入，不采用。

## Regression verification

- `AttachmentE2eDeviceTest.unsupportedTypesAreStablyRefusedWithoutSideEffects`
  对 PDF、OLE2 DOC、WAVE 和 UTF-16LE 逐一断言稳定拒绝、pending 为空、wire call 为 0，
  且 `input/attachments` 不存在或为空。
- 该测试包含在合并后的 `:app:connectedConsumerDebugAndroidTest` 与
  `:app:connectedDeveloperDebugAndroidTest` API 29/36 全量回归中，四组均 0 失败。

## Residual risk

若底层文件系统拒绝单文件删除，拒绝结果仍保持 fail closed，但可能保留不可清理文件；
未来若出现可重复的删除失败，需要加入有界、只针对未注册 attachment-id 的回收机制，
不能扩大到递归清理整个附件根目录。

## Related records

- [ADR-0014：会话附件物化](../adr/0014-session-attachment-materialization.md)
- [文件工具安全边界](2026-09-02-file-tool-safety-boundaries.md)
- [HXA-056 完成记录](../completion-records/HXA-056.md)
