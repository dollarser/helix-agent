# HXA-283：有界 PDF 读取与总结主机交付

日期：2026-10-14。范围：[HXA-283](../../development/tasks/HXA-283.md)、[ADR-WORKSPACE-006](../../adr/workspace/006-pdf-reading.md)。Owner要求PDF和插件/MCP都推进；本切片只做本地主机开发/编译，不启动设备、真实模型、长稳或服务器调查。测试来源为`be78e77f`上的工作树增量；制品不是clean正式发行锚点，未安装到日常模拟器或手机。

## 实现与依据

- 两渠道注册`files.read_pdf`：一次一页、原文件只读、源冻结字节hash、页数/下一页、真实截断；`auto`优先文本、缺文字时渲染，`image`核查图表/版式。总结由当前模型按已读页生成，不另调模型、不虚构全篇。
- 现有PdfTextSupport是有界纯JVM简单预览，保留其测试和功能；纠正PDF预览“全文内联”及让模型读原始二进制的提示，改为不完整预览/逐页工具，并在两条生产附件路径绑定原scope。普通文本/图片路径与持久化规则保持。
- 原生PdfRenderer只在主APK内非导出isolated UID服务，拒绝非隔离Binder，合法唯一实例名；不建独立库测试宿主、不新增依赖/数据库schema/Provider协议/常驻工具框架。只传只读源FD、仅写页面FD和必要元数据，不初始化AppContainer/Room或账号。
- 20MiB源、20000字符文字、1600长边/524288像素、3MiB PNG及150000字节工具输出；原deadline与取消沿用。客户端取消和服务30秒寿命后备仅结束自有实例；槽位由实际Binder死亡释放，连接断开不冒充退出，死亡通知先更新容量事实再唤醒等待。退出未确认时拒绝消费输出，保留容量直到死亡；取消/迟到清理不能释放新实例的槽位。
- 页面图像复用现有Artifact/vision和内容/接收方披露，`acquisitionScopeRef=null`，不冒用Mobile Use许可。普通源文件读取继续目录冻结、Dispatcher文件读权限/禁用与审计。
- 参考Android官方隔离/原生API与Claude PDF文字/视觉组合，采用有界按页设计，不复制参考代码；来源和取舍在ADR。

## 实际验证

所有Gradle/全仓检查持有既有`with-host-slot.py`。忽略日志在`build/hxa283/`；较早失败尝试保留在`build/hxa282/mobile-observation-optimization/pdf-*`。

| 检查 | 当前结果 |
| --- | --- |
| 最终`./scripts/check-all.sh --all`（`all-final-r6.txt`） | 退出0；源码/Python、文档/ADR、i18n/secret、Spotless/Detekt、完整JVM、两渠道Debug/Release lint/APK及实际包边界通过 |
| 两渠道AndroidTest编译（`compile-final-r6.txt`） | 退出0；新增PdfReadingDeviceTest三方法已编译，未运行 |
| 文件工具JVM | 130方法通过；新PDF工具6、源快照3，0失败/错误/skip |
| App相关四类回归 | consumer/developer各52方法通过，含新容量3、PDF预览1、目录冻结1及文件读取分类1，0失败/错误/skip |
| PDF进程Python边界 | 2方法通过；最终APK四种组合也检查实际非导出、隔离UID和独立进程 |
| 设备/真实模型 | not requested；没有原生渲染/IPC退出/超时看门狗或模型摘要质量的实际设备通过记录 |

覆盖正常分页/文档身份、typed图片与屏幕许可隔离、视觉不可用不假装已读、越界路径/页码、取消/过期/迟到结果、损坏/加密错误传播、长度/源限制、原生容量取消不提前释放和旧回调不影响新实例。没有删改原文档解析测试来换通过。

早期格式/语法、AndroidTest使用非Closeable/错误scope访问、任务/ADR文档结构、Detekt职责拆分及UseKtx lint失败已修正；未关闭门禁。`all-final-r5.txt`曾全通过，之后复核并修正退出回执顺序，最终r6重新全量通过；不把r5当最终源码结果。主机Python负向fixture打印的FAIL/INCONCLUSIVE是受测模拟输入，不是执行了设备长测。

## 制品

| 产物（`app/build/outputs/apk/`） | SHA-256 |
| --- | --- |
| consumer/debug/app-consumer-debug.apk | `21b701bc4c2587e96827067af44fef65a3fd5d8b7f601f3d67d7a2494c9bf7b2` |
| developer/debug/app-developer-debug.apk | `e2a4f96fbb56d61f4bdb4327e7287ad3a7db20c34cc66f155f4b098d9445f6ad` |
| androidTest/consumer/debug/app-consumer-debug-androidTest.apk | `6ebd27fa491e1d1ce6d6b94a6805ea7d3ec68c9af7f62eb6ca4ac886ae08fec4` |
| androidTest/developer/debug/app-developer-debug-androidTest.apk | `984e77731fbf2441c1e5bbf3e1a3421811b2dc7b67f940ff1d48e8928ebc533b` |

## 验证边界

Android35+才有平台文字提取；API29–34和扫描件依赖视觉模型，视觉未交付明确CONTENT_UNAVAILABLE。原生解析内部不是固定堆配额，复杂字体/恶意文档与OEM资源行为仍须设备证据；小字/多栏/表格受渲染和提取顺序限制。密码保护、OCR、编辑/生成和Office不在本切片。PDF内容仍是不可信数据，不授权后续工具动作。

已准备合成两页真实PDF设备夹具：包边界、API文字/图像分支、指定页、源不变、页码越界、损坏与过期。下次按Owner明确设备范围执行，再独立验原生取消/进程退出及真实模型总结；本轮不提前填passed。真实Notion/Linear账号配置路径和正式发行身份仍由原任务承接，不阻塞此主机开发。
