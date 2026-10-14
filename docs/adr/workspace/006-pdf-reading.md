# ADR-WORKSPACE-006: 有界 PDF 读取与总结

Status: accepted
Date: 2026-10-14
HXA: HXA-283
Deciders: project owner（功能范围：按优先级推进，PDF与插件/MCP回复“都要做”）；实现Agent（记录下述范围内的原生隔离选型）

## Context

已有附件的纯JVM简单PDF文本预览（PdfTextSupport）有有界解压与长度保护，但不是带字体、扫描页或版式的完整读取。附件导入和二进制read不等于PDF页面内容理解。Owner已批准首个PDF读取/总结工作流。Android原生PdfRenderer支持渲染；文字提取始于API35，旧版本只能通过页面图像理解。

参考[Claude PDF支持](https://platform.claude.com/docs/en/build-with-claude/pdf-support)的逐页文字/视觉组合，采用按页检索、明确未读页和截断；不照搬其云端整文上传或大小限制。[Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)建议不可信PDF在最小权限隔离进程解析；[Page](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)给出API35文字提取边界。未复制参考代码、无新增第三方依赖。

## Decision

- 两渠道提供`files.read_pdf`，一次一页，1-based页码，默认auto（有可提取文字优先文字，否则页面图像），可显式image查看图表。返回总页数/下一页/源快照hash/截断事实。总结由当前模型依据已读取页生成；工具不伪造摘要、不把局部读取宣称全篇。
- 原文件经既有目录冻结、Dispatcher文件读取分类和WorkspaceArtifactStore授权读取；单次源快照最多20MiB，临时私有缓冲不可由模型指定目标，绝不写回源文件。扫描件和API29–34需要现有视觉模型能力；不内置OCR、不上传原PDF、不新增Provider协议。
- 原生解析仅在非导出、isolated UID的`:pdf_reader`服务中，服务只拿到只读源FD/单个衍生图像输出FD和页码/模式，不创建AppContainer、不读Room/凭据、无网络权限。每次使用合法字符的唯一isolated实例；服务拒绝在非隔离进程返回Binder，并有30秒自身寿命后备。一次物理解析，取消/超时通过终止自有隔离进程中止，不回收尚未退出的物理容量；进程死亡/解析失败均显式报告。
- 每页文字至多20000字符，渲染长边至多1600、像素至多524288，PNG最多3MiB；异常页尺寸拒绝，页码越界/加密或损坏文件失败，无无界批量或自动重试。长文按页读取，超限要求用户拆分。既有附件纯JVM尽力文本预览保留，但PDF不得标为全文内联；模型上下文提供已绑定源scope的原始PDF引用，明确通过本工具读取和核查。
- 页面图像复用既有Artifact/vision披露和最终接收方校验，不冒用Mobile Use屏幕许可；仅必要宿主临时/衍生观察产物属于既有只读工具缓冲，不提供模型任意写路径。原源文件权限不等于像素外发授权。
- 生命周期、权限、tool禁用、取消、原call身份及审计仍走唯一现有管线。主机/编译完成不等于设备、真实模型或业务摘要正确性通过。

## Alternatives considered

- 在主进程调用PdfRenderer：实现短，但不可信输入的原生解析会影响宿主；不采用。
- 新增PDF第三方库或独立OCR/PRoot流程：增加依赖、许可证与渠道差异；当前平台文本/视觉足以支撑首个读取任务，不采用。
- 整文直接上传云模型：依赖Provider专有PDF协议且改变披露范围；当前复用本地分页和已有图片接收方校验。
- 长期缓存打开的renderer：需要跨调用可变资源身份和额外回收；首版每次冻结源字节、单页解析后关闭自有实例，跨页hash变化明确告知。

## Consequences

两渠道共享工具与解析边界，没有新依赖、数据库迁移或永久授权。每次读取有源复制和隔离进程启动成本；旧Android/扫描件需要视觉能力，版式/小字可能受渲染像素上限影响，不保证OCR准确率。文本顺序沿平台提取，复杂表格/多栏需显式image核查。源hash标识本次实际解析的冻结字节，不声称原文件读取过程具有文件系统事务原子性。

隔离进程只持有两个有限用途FD；Java输出和宿主接收均有界，native请求完成后还须确认进程退出才消费图像。取消/超时不释放尚在执行的native容量。输入字节/页图/输出限制不等于原生解析内部有固定堆配额，设备资源行为须实际验收；不宣称能消除所有恶意PDF或OEM风险。

## Verification

HXA-283要求源码、JVM、静态检查、双渠道lint与Debug/Release APK、AndroidTest编译及实际APK隔离声明。设备夹具用合成两页PDF验证实际文本/图像、越界/损坏输入和源文件不变；未获本轮设备/模型授权时仅编译、不填设备通过。

## Reconsider when

出现实际PDF内容质量/资源/平台兼容问题，或Owner接受OCR、区域高清读取、更多格式/生成编辑、云端原PDF协议时，基于样本和许可重新评估同一ADR，不自动加执行依赖。

## References

- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)：隔离进程、FD和工作线程边界。
- [Android Page](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)：API35文字及原生渲染。
- [Claude PDF支持](https://platform.claude.com/docs/en/build-with-claude/pdf-support)：文字/页面视觉结合，采用设计原则，不复制实现。
- [权限ADR](../permissions/001-session-authorization.md)：源读取、内部缓冲与视觉披露分别校验。

## Decision history

- 2026-10-14：沿Owner已授权读取/总结范围采用原生隔离、逐页文本/视觉方案；排除编辑、生成、Office和云端PDF上传。
