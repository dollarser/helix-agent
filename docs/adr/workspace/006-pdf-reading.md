# ADR-WORKSPACE-006: 有界文档读取与总结

Status: accepted
Date: 2026-10-14
HXA: HXA-283, HXA-284
Deciders: project owner（功能范围：按优先级推进，PDF与插件/MCP回复“都要做”）；实现Agent（记录下述范围内的原生隔离选型）

## Context

已有附件的纯JVM简单PDF文本预览（PdfTextSupport）有有界解压与长度保护，但不是带字体、扫描页或版式的完整读取。附件导入和二进制read不等于PDF页面内容理解。Owner已批准首个PDF读取/总结工作流。Android原生PdfRenderer支持渲染；文字提取始于API35，旧版本只能通过页面图像理解。

参考[Claude PDF支持](https://platform.claude.com/docs/en/build-with-claude/pdf-support)的逐页文字/视觉组合，采用按页检索、明确未读页和截断；不照搬其云端整文上传或大小限制。[Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)建议不可信PDF在最小权限隔离进程解析；[Page](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)给出API35文字提取边界。未复制参考代码、无新增第三方依赖。

## Decision

- 两渠道提供一个随APK交付的原生`documents`插件，包含`documents.inspect/read/view`和按需加载的`document-reading` Skill。PDF、DOCX各自解析，共用选择、文件权限、Dispatcher、审计及视觉管线；删除等效`files.read_pdf`，不维护兼容别名，不新增AgentLoop、CLI或联网后端。
- `inspect(path)`在冻结来源授权下复制最多20MiB原字节，hash针对实际快照。既有FileContentStore保存二进制，最小Room索引保存会话、原sourcePath、格式及ContentRef；生成documentRef不是Bearer授权。`read/view`先按索引将原sourcePath交回当前会话文件权限分类，再重新验证原来源后端可读和快照hash。跨会话/撤权/缺失缓存拒绝；源文件变化不替换旧快照，显式inspect才能取得新版本。复制不声称文件系统原子快照。
- 索引与Session级联删除；既有publication锁和GC保护仍被引用的正文/二进制，会话删除只回收无剩余引用的内容。不保留跨调用renderer/XML对象作为权威状态。开发期Room v1基线增加一张表和Artifact目的字段；沿现有开发库重建规则，不新增历史迁移。本轮不安装设备APK。
- 所有实际PDF/DOCX解析在非导出、isolated UID的`:document_reader`一次性作业实例中。只传只读源FD、宿主管理的写出FD和有界参数，不传目录、凭据或网络能力，不初始化AppContainer。物理容量为一个文档作业；取消/超时终止自有实例，确认Binder死亡后才释放，未退出不消费输出。工具20秒、进程寿命30秒后备；不引入全局文件执行锁。
- PDF一次一页，1-based；inspect返回总页数，read提取API35+文字（旧API/扫描页明确不可提取），view观察实际页面/归一化区域。文字至多20000字符，直接按区域缩放平移渲染至长边1600、总像素524288以内、PNG3MiB以内，不先渲染无界高分辨率再裁剪。复杂表格/多栏须视觉核查，不内置OCR。
- DOCX依据OPC关系和WordprocessingML实际主体结构读取段落、标题样式、表格行/列及内嵌图片关系，按真实内容块定位，不提供Word页码或高保真排版。每次最多32块/20000字符、大表按行继续，下一cursor指向第一个未返回块。单次返回至多256单元格、每块16图像引用，遗漏/截断显式标注；整个包图片关系至多2048、关系表8192项。
- DOCX最多2048成员、单XML8MiB、实际累计解压64MiB。拒绝重复/穿越成员、关系越界、错误根命名空间、DTD/实体和过深XML；外链从不请求，宏/OLE不执行。PNG/JPEG先核验字节，再在隔离进程探测/有界解码（原图至多16777216像素），再走宿主统一图像归一化。WMF/EMF、图表/SmartArt/公式、嵌入对象及正文外headers/notes等明确partial或unsupported，建议需要时提供PDF。
- 输出固定源hash、实际page/block/image及coverage、warnings、truncated和下一位置。`imagePrepared`只表示图像准备事实，不证明后续模型已接收；现有逐请求视觉窗口/能力/接收方/数据发送检查仍可拒绝或淘汰，不借用Mobile Use屏幕授权。文档内容是资料，不能授权后续操作。正文与图像不等于可任意外发。
- 附件PDF/DOCX仅作预览，不再声称全文；附上文档不自动选插件。已有文件到会话入口保留，缺少插件时提供显式选用入口；会话另列文档来源/hash，当前预览只打开原文件并提示变化，不承诺精确跳页。文档观察Artifact标为INTERNAL_OBSERVATION，不默认列入成果，仍保留真实会话/Turn/视觉引用。保存总结沿普通write及读回，读取不会隐式创建交付文件或改原文。

## Alternatives considered

- 在主进程调用PdfRenderer：实现短，但不可信输入的原生解析会影响宿主；不采用。
- 新增PDF第三方库或独立OCR/PRoot流程：增加依赖、许可证与渠道差异；当前平台文本/视觉足以支撑首个读取任务，不采用。
- 整文直接上传云模型：依赖Provider专有PDF协议且改变披露范围；当前复用本地分页和已有图片接收方校验。
- 长期缓存打开的renderer：需要跨调用可变资源身份和额外回收；首次inspect冻结源字节、随后复用不可变内容，每作业关闭自有实例，跨页hash变化明确告知。

## Consequences

两渠道使用平台PDF和标准ZIP/SAX，无新增第三方解析依赖。跨调用快照增加私有内容存储及最小Room事实，避免同名文件变化造成混读；GC、会话删除和恢复继续沿原存储生命周期。导出/分叉后旧会话documentRef不自动取得新会话授权，缺失或外来引用要求重新inspect。

DOCX是主体结构读取，不是Word分页/修订/浮动布局渲染器；复杂Office对象可能需要PDF导出。扫描PDF和API29–34需要视觉模型。预算约束输入/解压/输出与图像分配，不等于native或整个隔离进程有固定PSS；设备峰值、平台SAX和实际渲染/取消仍需当轮授权验收。

## Verification

HXA-284要求源码、JVM、静态检查、双渠道lint与Debug/Release APK、AndroidTest编译及实际APK隔离声明。设备夹具用合成两页PDF和中文DOCX验证实际文本/页面区域/内嵌图像、越界/损坏输入和源文件不变；未获本轮设备/模型授权时仅编译、不填设备通过。

## Reconsider when

出现实际PDF内容质量/资源/平台兼容问题，或Owner接受OCR、区域高清读取、更多格式/生成编辑、云端原PDF协议时，基于样本和许可重新评估同一ADR，不自动加执行依赖。

## References

- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)：隔离进程、FD和工作线程边界。
- [Android Page](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)：API35文字及原生渲染。
- [Claude PDF支持](https://platform.claude.com/docs/en/build-with-claude/pdf-support)：文字/页面视觉结合，采用设计原则，不复制实现。
- [权限ADR](../permissions/001-session-authorization.md)：源读取、内部缓冲与视觉披露分别校验。

## Decision history

- 2026-10-14：沿Owner已授权读取/总结范围采用原生隔离、逐页文本/视觉方案；排除编辑、生成、Office和云端PDF上传。

- 2026-10-14：Owner明确授权统一PDF/DOCX插件；采用Claude插件的组件组织和按需Skill、WordprocessingML结构读取，延续Android原生PDF与隔离边界。参考 https://code.claude.com/docs/en/plugins 、https://learn.microsoft.com/en-us/office/open-xml/word/structure-of-a-wordprocessingml-document 。无参考源码复制或新依赖。
