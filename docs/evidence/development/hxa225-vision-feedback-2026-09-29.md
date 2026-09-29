# HXA-225 自主读图主机交付证据

日期：2026-09-29。范围：Agent 主动文件读图、浏览器视觉产物、三协议请求、绑定/预算/披露及现有订阅图片 IPC。设备/模型/账号状态：`not requested`。没有 commit、push、安装、清库或真实服务调用。

## 1. 源码与 schema

- 读取/构建的本地分支：main，HEAD `4a1443df06fced429d59ab7f12f5da221410b6a9`，带原有未提交工作。
- `scripts/debug/2026-09-29/hxa225-host-evidence.py` 汇总 2197 个被跟踪或当前非忽略的 Kotlin/Gradle/XML/锁及 schema 文件；源码指纹：`cd30fd9de679cb3c27bc2bb129cfefe3857b950c0be9632f56de7f0ff9d44276`。这是整份当前构建输入的有界文件集，不只包含本任务 diff，也不代替 Git commit。
- schema：version 1、50 张业务表；SHA-256 `fc5b0a5b177ce0ac98d3f9bd4f783d00c906742f04ba3f38c63ef6b005334c1c`，与本任务开始前完成草稿表删除后的基线相同；没有新增表或重建旧草稿表。
- 机器可读汇总在忽略的 `build/evidence/hxa225-host-2026-09-29.json`，包含逐 suite 的 tests/failures/errors/skipped、精选视觉测试与制品哈希。原始 JUnit XML 位于各模块 `build/test-results`，不是永久仓库文件。

## 2. 实际命令与结果

定向回归：

```bash
python3 scripts/with-host-slot.py -- ./gradlew spotlessApply detekt \
  :core:model:test :core:storage:testDebugUnitTest :tools:framework:test :tools:files:test \
  :tools:browser:testDebugUnitTest :provider:openai-responses:test :provider:openai-chat:test \
  :provider:anthropic:test :runtime:cli-client:testDebugUnitTest \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

最终定向执行 Job `df444541-4d5f-4f68-927f-f81366cdbf77`，exit 0，BUILD SUCCESSFUL，530 actionable tasks。之后唯一 Kotlin 改动是 Runtime 注释澄清，最终全量门禁重新覆盖了它。

全量主机：

```bash
python3 scripts/with-host-slot.py -- ./scripts/check-all.sh --all
```

最终 Job `61e09e0f-a6a2-4311-a3d4-8ba6e043a17f`，exit 0。实际覆盖 source、ADR/i18n/secret、Spotless/detekt、Debug/Release lint、全工程 tests、consumer/developer Debug/Release APK、38 份锁以及 Debug/Release 制品的组件、进程/UID、payload、launcher 与订阅边界。Gradle 的 UP-TO-DATE 保持真实记录，不伪称全部重新执行或 cold build；它们来自同任务先前成功模块和最终输入校验。

统计与最终文档检查使用：

```bash
python3 scripts/debug/2026-09-29/hxa225-host-evidence.py --output build/evidence/hxa225-host-2026-09-29.json
python3 scripts/with-host-slot.py -- ./gradlew spotlessCheck
./scripts/check-all.sh --source
git diff --check
```

统计脚本只读取主机文件，不操作设备或模型。最终 Spotless 通过；新完成记录补齐规定的“决策记录：”标签后，source＋diff 复核 Job `9e583bf7-744f-44ae-b15b-28ab4fb49a63` exit 0：624 Markdown、214 HXA、35 ADR、1803 i18n keys 及 secret scan 全部通过。随后再采集的源码指纹与上述值一致，所有记录 suite 的 failure/error 为 0；没有用旧文档门禁代替新交付记录检查。

## 3. 测试分布

| suite | tests | passed | skipped | failure/error |
| --- | ---: | ---: | ---: | ---: |
| app Consumer Debug | 917 | 913 | 4 | 0 |
| app Developer Debug | 965 | 961 | 4 | 0 |
| core/storage Debug | 217 | 217 | 0 | 0 |
| tools/files | 120 | 120 | 0 | 0 |
| tools/framework | 204 | 204 | 0 | 0 |
| runtime/cli-client Debug | 44 | 44 | 0 | 0 |

视觉相关独立测试类：

| 类 | 场景数 | 覆盖 |
| --- | ---: | --- |
| ToolImageContractTest | 8 | 角色、图像绑定、sidecar、无图提示的互斥与字段限制 |
| ViewImageToolTest | 4 | 工具声明、路径/调用与可信图像准备 |
| ToolImageEncodingTest | 每协议 2 | Responses/Chat Completions/Anthropic 真实图像结构和工具配对 |
| ToolVisualFeedbackTest | 6/渠道 | 拒绝、未支持、跨 Turn、来源缺失/变化、回填 |
| ToolImageWindowTest | 5/渠道 | 当前 Turn 最新窗口与总图片预算 |
| ToolVisionConsentTest | 4/渠道 | 精确批准、重用、拒绝/过期/取消等 |
| VerifiedImageBytesTest | 7/渠道 | 过大元数据、源增长/无界流、截短、同尺寸篡改、MIME、停滞与取消 |
| ToolImageProjectionContractTest | 3/渠道 | 无图提示不改历史、压缩匹配不放松、全编码路径和输入估算 |
| CliToolImageCodecTest | 4 | TOOL role/call、去重相同字节、拒绝冲突字节、无图提示 IPC |
| SubscriptionImageSnapshotsTest | 3（developer） | 使用逐图/目标解析；拒绝不可回退全局指针；无图不打开图片源 |

FileToolArguments、SessionToolEffectClassifier、ArtifactVisionImageSource 和 BrowserToolsMapping 也补充或运行对应回归。渠道重复项与既有测试不能直接累加成新增独立功能数。

## 4. 失败发现与修订

保留有诊断价值的失败，不删除/跳过测试，不放宽门禁：

1. 中断前的 App 容器和设备测试函数触发复杂度/长度限制；按职责提取 ToolVisionServices 与 fixture helper 后通过，没有删除像素和身份断言。
2. 独立复审发现旧图未展示说明直接追加正文会破坏压缩历史匹配；增加封闭 ToolImageOmission，仅 wire/预算使用 modelText，canonical 历史仍严格匹配。
3. 旧图片校验先 hash 后整读不保证同一字节且可能无界；改为 final bounded read 后一起 hash/MIME/长度校验并覆盖恶意源。
4. 新协议测试第一次漏了 assistant 工具调用，Anthropic 正确拒绝。修正测试构造完整调用批次，不修改 encoder 放过孤儿工具结果。Browser description 换行触发的格式问题已修正。
5. 全量门禁第一次因任务文档缺少规定标题停止；修订 HXA-225 规格标题和验证入口，没有修改 check-docs 规则。
6. 随后全量构建/测试通过，但 CLI 边界扫描命中新注释中的 `authorization`。逐行确认仅注释命中且线协议无凭据/批准字段；注释改为准确的 App checks before IPC，边界扫描不变。最终重新执行完整命令 exit 0。

## 5. 制品

| 文件 | SHA-256 |
| --- | --- |
| consumer Debug APK | `554e678c3360c0f4c20eb2213ba28cb065fc0a32272c4676ee098498e5411895` |
| developer Debug APK | `769b6689107075cfbdbdc977f640ab5d98de3d9c7af405445b303577602b4924` |
| consumer unsigned Release APK | `66dfdd93c19edf2101da88c8d22d5b5d7c5e461d622bc21b6cb25ba18efdfe61` |
| developer unsigned Release APK | `38e5f8140687754abed2b3e2995ec18b795b1bbb7d3d50c4dd2db4296098a73c` |
| consumer AndroidTest APK | `c0234def77fe6d1dc21b425026ce50795c83b4dee2e425806d3aed23d8b2cbbe` |
| developer AndroidTest APK | `703ca5f06d42ac052a3f624bea6d296efe4ce2cecbca7b658d4f1fa56c051fc0` |

标准输出及报告可能有有界截断；终态 exit 0 与明确 gate 摘要均已读取，测试数量来自实际 XML。没有把未读取的完整日志内容补写成事实。

## 6. 未覆盖边界

- `ToolVisionFlowDeviceTest` 的真实 PNG/Room/Android 位图链路只有编译证据，没有执行；模型像素识别、拒绝对话框、取消、恢复和 OEM 内存仍需要当前任务明确授权。
- 没有向 SGLang、云模型或订阅账号发送图片。协议 fixture 只证明编码，不能证明特定第三方服务接受全部格式或模型会正确识别。
- 用户手机仍是前一任务安装的版本，本轮不操作安装和数据；该功能需要安装新制品才会生效。
- 不实现内置本地多模态、Mobile Use 截屏获取、OCR、视频和任意 MCP 图片上传。API 兼容差异明确失败，不另用协议或账号绕过。

[完成记录](../../completion-records/HXA-225.md) · [长期契约](../../adr/agent/011-tool-multimodal-vision-feedback.md) · [使用与竞品取舍](../../product/image-reading.md)
