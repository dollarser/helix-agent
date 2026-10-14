# 统一文档插件：主机交付（2026-10-14）

范围：[HXA-284](../../development/tasks/HXA-284.md)、[ADR-WORKSPACE-006](../../adr/workspace/006-pdf-reading.md)。Owner授权按统一PDF/DOCX方案实施，可继续调研；本轮设备/真实模型not requested。起点dd36b673；本记录只证明下列主机范围。

## 实现

- 一个documents原生插件，inspect/read/view、完整发现操作组、document-reading按需Skill。PDF/DOCX预览不冒充全文，附件未选插件提供选择入口，不自动启用。
- Room会话索引与既有ContentStore二进制publication/GC共用不可变内容，逐调用按原来源重验会话和权限。源变化显式inspect，外来/丢失内容失败，不回退同名文件；永久删除保护其他会话仍引用内容。
- 一次性isolated UID文档服务，只传源只读FD/图像输出FD及结构参数；实际死亡后才释放容量。PDF平台文本/页面/直接区域渲染，DOCX有界ZIP/SAX主体段落/标题样式、表格行/列及图像关系。
- 拒绝DTD/实体、根命名空间伪装、关系/成员穿越、重复成员、解压/成员/XML/图像超限，外链不获取、宏/OLE不执行，复杂对象/未覆盖范围明确partial。
- 文档图像走既有归一化、会话/Turn绑定与最终发送检查；imagePrepared不等于像素已发送。INTERNAL_OBSERVATION仍保留引用但不进入普通成果列表。来源面板打开当前原文件并提示变化，位置以页/块及hash标注，不承诺精确跳转。
- 删除旧files.read_pdf工具/port，原测试覆盖迁入新插件、快照仓库与实际隔离夹具；没有兼容别名、外部引擎/新依赖或账号操作。

## 参考与取舍

参考[Claude插件组织](https://code.claude.com/docs/en/plugins)的组件同包/Skill按需加载；采用[Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)及[WordprocessingML结构](https://learn.microsoft.com/en-us/office/open-xml/word/structure-of-a-wordprocessingml-document)。保留Android授权和持久状态边界，不搬入桌面Python/LibreOffice，不复制参考源码。平台PDF适配和可终止Binder生命周期由App文档工作进程承接，DOCX包/结构读取位于extensions:documents，经同一DocumentParser端口调用。

## 验证

所有命令经现有with-host-slot主机槽执行，原日志留在忽略的build/hxa284/：

| 命令/记录 | 结果与范围 |
| --- | --- |
| ./scripts/check-all.sh --all；all-final-r4.txt | 退出0；Python228、源码/文档/ADR/i18n/secrets、全仓JVM、静态分析、两渠道Debug/Release lint/APK、隔离/渠道/Runtime声明、41份依赖锁核对通过 |
| :extensions:documents:testDebugUnitTest | 27项：DOCX18、工具7、真实随包内容/插件选择禁用2；0失败 |
| :core:storage:testDebugUnitTest相关类 | 二进制5、不可变快照4、schema7，共16；包括源变化/重启、跨会话、撤权、失效/篡改、取消和GC |
| :app:testDeveloperDebugUnitTest相关类；unit-r11.txt | 55项：容量3、权限29、附件9、目录绑定12、发现组2；0失败；全仓随后重跑其他App测试 |
| :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest；content-and-test-apks-r1.txt | 退出0，生成两渠道测试APK。四项原生PDF/区域/DOCX夹具只编译，未运行 |

### 失败与修正

初轮编译/格式化与unit-r1～r9发现字节比较、预览新文案、schema清单及Artifact假DAO需随契约更新；解析输出在文字预算边缘跳过中间块的问题已改为连续游标，表格行定位按各表重新计数，新增回归。未删除或放宽失败/取消/安全场景。

all-r1/r2未通过：工具说明分行触发TextConcatSpace、复杂条件/夹具方法及格式错误；均修正。all-r3完整门禁通过后，真实资源复核发现Skill加载使用插件名documents，违背目录document-reading契约；修正后增加真实资源加载及会话选用/禁用回归，并重新跑all-final-r4，不继承旧绿。

最终all-final-r4中Kotlin增量编译报告DirtyData缓存异常，但同一最终命令随后分析/构建退出0；不据此声称已证明编译器内部回退细节。原异常保留，不当作应用运行失败，也未通过改生产逻辑或降低门禁处理。本机执行服务短暂断开的一次命令未启动，连接恢复后继续，没有丢失设备测试或长稳任务，因为本轮未启动它们。

## 制品

以下均本机生成、未安装/发布；Release为unsigned。SHA-256：

| 路径 | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/consumer/debug/app-consumer-debug.apk` | `40293af9f88c0aa4aa7dd87ab888da06e38b2d6b78777244034e7106a882635f` |
| `app/build/outputs/apk/developer/debug/app-developer-debug.apk` | `e581af8d2507c1b6b8888f17906ce28a392d798681b189139261f5937252ae75` |
| `app/build/outputs/apk/consumer/release/app-consumer-release-unsigned.apk` | `4654db53a25377b92460bebb5cc5dd2fc4f97462363861ebf1dd7f8bd101cc2d` |
| `app/build/outputs/apk/developer/release/app-developer-release-unsigned.apk` | `e8e2de63c2649b766d718681617e723525b4d10b6d841369bcc16a171622e638` |
| `app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk` | `07e3329dd9c8fe088a9154bcae1e3eb16e3aabf9c007e0a6166965b3e1dd7245` |
| `app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk` | `cb9df5bef8f4c1208e989c06e6b11d8734dba75dec07f2392365692d8a3930a2` |

## 验证边界

设备/模型not requested；未连接/操作设备、未改服务端、未调用Qwen/其他模型或真实账号。没有PDF/DOCX答案准确性、Android SAX/PdfRenderer实际兼容、像素裁剪业务正确性、Binder死亡/FD/PSS峰值或长稳验收。API29–34 PDF文字依赖视觉；DOCX不提供Word分页/完整排版、OCR、生成编辑，正文外故事和复杂对象明确未覆盖。

新增开发库Room v1基线会使旧开发库不兼容，下次覆盖安装前须按项目规则报告风险并获确认/备份；本轮未安装或操作现有数据库。来源预览打开当前原文件，旧hash不保证对应当前原文；过期引用应重新inspect。仍被会话引用的内部内容不作无引用LRU清除。
