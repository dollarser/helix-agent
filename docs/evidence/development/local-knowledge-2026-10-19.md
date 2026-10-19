# 本机知识库第一阶段验收（2026-10-19）

范围：[HXA-308](../../development/tasks/HXA-308.md)、[ADR-WORKSPACE-007](../../adr/workspace/007-local-knowledge.md)。起点 `3031b22d`，初始工作区干净。先核对既有语音/分支/向外分享收尾，没有恢复被删除的单消息分享。当前任务新增的是**接收系统分享**。本地交付，不 push/发布；版本 0.0.6 / code 1、工具声明版本 1 不变。

## 实际交付

- 系统 SEND/SEND_MULTIPLE 进入独立来源库，保存文字、URL、原件、来源及时间，再异步提取。临时 URI 在授权有效期间复制；实际字节去重、文件/文字预算和中断状态有界，失败保留已取得资料。
- 原始来源、提取文本、来源附属摘要与知识笔记分离。支持手写、单源提炼、多源综合、草稿/核对保存；笔记和多对多引用快照原子保存，重新提取不覆盖笔记。一个新增 `library.db` 保存来源/笔记/引用/FTS，原件在私有目录；没有旧版迁移、兼容层、向量服务或同步账号。
- 中英文检索覆盖文本与文件名；所选资料可引用到聊天而不自动发送、不强制存笔记。没有全库上下文注入或人格记忆写入。
- 复用现有 Provider 显式选择模型，确认目标/内容范围后才分析。建议与用户正文分开，明确载入编辑并核对保存；模型失败、取消、重启不会自动重试或覆盖用户正文。外部来源仅作不可信资料。
- 公共 URL 使用既有受限 transport；真实 example.com 获取成功，仍诚实标记通用提取未核验。登录页/仅标题/图片/未知文件不冒充完整正文或 OCR。微信内部转发不保证有 Android 系统分享入口，界面与 README 有说明。

参考 Reader、Obsidian Clipper/Interpreter/Templates、Joplin、Karakeep、Omnivore、NotebookLM 和 Android 官方实践；采用与未采用的部分见 ADR，没有复制参考项目代码。

## 主机与产物

最终成功命令及本机日志（`build/` 不入 Git）：

| 检查 | 结果与证据 |
| --- | --- |
| `:app:assembleConsumerDebug :app:assembleDeveloperDebug`、两渠道 AndroidTest、两渠道 JVM、`:core:storage:testDebugUnitTest :core:agent:test`、两渠道 Debug lint | 成功；`build/knowledge-debug-final.log` |
| `:app:assembleConsumerRelease :app:assembleDeveloperRelease` | 双渠道 R8/Release 成功；`build/knowledge-release-final.log` |
| 最终测试 APK 重编、`spotlessCheck detekt` | 成功；`build/knowledge-test-final.log`；最后只修正测试的 JUnit 返回类型及冷启动等待预算，产品 APK 字节未变 |
| `scripts/check-all.sh --source` | 主机脚本 279 项通过，文档/ADR/i18n/秘密扫描通过；`build/knowledge-source-final.log`。其中 FAIL/INCONCLUSIVE 日志来自验证门禁本身的负面夹具，非本轮设备失败 |
| JVM XML 汇总 | Standard 1313 项、Advanced 1448 项（各 4 项外部 Connector/profile 既有条件跳过，未记通过）；Storage 242、Agent 293 项，无失败/错误 |

最终 APK 和测试 APK 复制至 `build/knowledge/deliverables/Helix-{Standard,Advanced}-{debug,test,release-unsigned}.apk`。`build/knowledge/evidence/provenance.json` 记录产品/测试源码输入摘要、JVM 报告及产物完整性；各设备安装后读取实际包路径校验字节与候选一致。Release 是未签名构建产物，设备功能验收使用普通 Debug，不代表商店或 R8 运行验收。

## 本次设备执行

Owner 当前任务明确授权日常 ARM64 模拟器及已连接 OnePlus 的合成验证。未启动其他设备，未录音、未清数据，测试仅用合成文本/图片/文件与公开 example.com。

| 设备 / API / ABI | 渠道 | 最终结果 |
| --- | --- | --- |
| 日常 emulator-5554 / API36 / ARM64 | Standard Debug | 27/27，33.660 秒 |
| 同一日常模拟器 / API36 / ARM64 | Advanced Debug | 27/27，34.529 秒 |
| OnePlus 6T / API34 / ARM64 | Advanced Debug | 27/27，25.458 秒 |
| 同一 OnePlus，现有内置 Qwen3.8-27B 服务 | Advanced Debug | 显式合成双来源模型验证 1/1，17.565 秒 |

27 项由 Collection 10、UI 6、Notes 7、GroupedNavigation 4 项组成：真实独立测试 UID 的系统分享入口/临时授权、复制后由 owner UID 撤销授权而原件仍可读；拒绝无授权、file URI、自身 FileProvider（含 user 前缀）；超限部分保留、去重、取消、持久状态恢复、公共页面与登录不完整、中文/英文/文件名检索、所选引用、手写/单源/多源笔记、冻结引用、明确审核、失败/取消/过时确认和伪造引用拒绝，以及短窗口/两倍字体导航。

模型测试通过现有生产 Provider 路径执行，没有新凭据或付费回退。预算 `137`、负责人 `TEAM_SYNTH_42` 仅在两份来源中提供，指令不提供答案；断言模型正文（排除应用追加引用页脚）保留两项事实及两个来源 ID，再断言建议仍为 DRAFT，明确保存才 REVIEWED。定向证据记录模型、输入/输出摘要，不存密钥；独立测试库/文件在 finally 清理。仅一次成功生成，不外推其他模型行为。

设备日志在 `build/knowledge/devices/<device>/<flavor>/{instrumentation,live}.log`；本轮 runner 与完整性归档脚本在忽略目录 `scripts/local/debug/2026-10-19/`。普通应用保留，三个自有测试 APK 已卸载。模拟器两渠道偏好文件（进程诊断除外）与麦克风权限前后相同；OnePlus 麦克风权限及其他偏好文件一致，`helix-ui.xml` 字节摘要变化：真实测试显式 `refreshModelCatalog` 会写现有模型目录证据缓存。没有旧 XML 的逐键快照，不声称 OnePlus 全部偏好逐字节不变，也没有为恢复摘要而覆盖用户设置。未修改模型选择/用户配置的代码路径，既有会话和文件不清理。

## 失败、修正与边界

早期回归暴露并修正了测试断言/夹具问题：Compose 文本子串匹配、分享自动打开详情后的多选定位、缓存目录不属于正式 FileProvider 根、example.com 标题不在正文、JUnit 表达式返回非 Unit。外部界面测试改为遍历 Compose accessibility 节点；并行 R8 构建期间 5 秒冷启动等待曾超时，单独原测试通过，调整为有界 15 秒后完整两渠道/真机通过。没有放松“实际可见内容、原件字节、撤权后可读”等效果断言。旧失败日志保留在本机 `build/knowledge/`，不将其算通过。

恢复测试通过持久状态夹具验证，不等于物理断电/进程杀死矩阵。没有登录 X/微信账号、私人资料、大库性能、全格式 PDF/OCR、其他 OEM、长稳或 Release 运行验收。公共抓取不保证文章完整。当前阶段无实现阻塞；后续提醒/TTS 仍未实施，按 Owner 接下来的任务推进。
