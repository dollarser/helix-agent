# 知识引用闭环与原生文件搜索收尾

日期：2026-10-26。范围：日常模拟器基线安装、合成知识引用真实模型验收、HXA-289 原生 glob／grep，以及权威计划状态同步。仅本地提交，不推送或发布，不改 v0.0.7 产物。

## 安装基线

按所有者针对性确认，Helix_API_36 / emulator-5554 上 Standard 与 Advanced 两个 Helix 包完成清空与安装。包名仍为 `com.helix.agent` / `com.helix.agent.developer`，均为 0.0.7 / versionCode 1；签名、安装字节及两库 schema 1 / Room 身份均核验。启动、知识／笔记／资料及引用选择取消烟测通过，模型调用为零。其他应用、共享工作区、真机及外部账号不在清理范围。原登录、配置及下载资源确已清除，不能声称保留数据。

主机基线来自 `b0d4370d`，规范提交 `d5380864` 仅文档；安装证据在忽略的 `build/internal-version-install-2026-10-26/`。该次初始安装尚不含后续搜索；下方最终日常包更新已接续。

## 本轮实现

- `glob` 路径模式、`grep` 字面量正文搜索复用受控 Workspace 存储；删除旧 FilesSearchTool / files.search 注册和模型工作流引用，没有旧执行别名。文件管理器的有效名称筛选保留。
- 参数绑定、模型目录投影、授权效果分类和工具目录同时更新。命中保留完整资源路径；grep 的 SHA-256 来自同一完整有界读取流。结果必须区分完整、截断、未读、编码／体积跳过；旧行号不是写入凭证。
- 不使用 Shell、不增加服务、依赖、索引或业务恢复状态机。glob 的 DP 匹配不执行用户正则；grep 明确只有 literal 模式，正则不是隐式降级。扫描、逐文件和总字节有上限，取消／deadline 不伪装完成，分页为重扫描而非快照。
- 原知识模型用例新增显式本地一次性登录、至多两次生成、实际 wire 引用正文核对、历史快照与源资料生命周期、回答保存笔记的原文和引用核对；只使用独立测试包与合成内容，不记录请求头或凭据。编译不等于该用例通过。

## 计划状态

总计划中仍写“287-C 未完成”的内容已与 HXA-287 及生命周期证据对齐。Memory 293-B 已完成有界对照，未证明索引候选胜过默认，不重新登记为尚未实验。HXA-295 B/C 的跨会话能力仍未实施，但宿主前置已经交付；不是再等完整引擎迁移。网页正文／Search／MCP Resources 等独立能力未因此实现。

## 验证记录

最终生产源码的 `GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Xmx6g' ./scripts/check-all.sh --debug` 通过：源代码／文档／ADR／本地化／秘密检查、两渠道 Lint、全 JVM、Debug APK、依赖锁和渠道／CLI 边界均通过。文件模块 140 项零失败零跳过，其中新增搜索契约 13 项；App Standard 1474、Advanced 1636 项零失败，各 5 项既有跳过。随后仅修复测试装配／清理，最终 `spotlessKotlinApply detekt` 和双渠道独立 AndroidTest 构建通过，没有以失败构建残留包验收。

最终独立 API36 ARM64：Advanced 4/4（知识真实模型 1＋原生搜索 3），Standard 原生搜索 3/3，零跳过。知识用例一次生成，实际 wire 证实两份正文进入请求，回答包含正确预算／负责人；历史引用在合成源删除后不变，回答保存笔记正文和来源完全一致，一次 Turn、零工具调用。缺失发布日期的回答表述未另设质量断言，不据此宣称所有知识问答准确或模型自主工具发现已稳定。

首次模型用例因误用通用目录入口等待超时；第二次观察器在登录前绑定账号 revision，与生产每调用新绑定不同，目录失败；两次均未进入生成，不能归因服务或模型质量。更正后第三次已进入真实生成，但 finally 重复删除合成源使 JUnit 失败，未冒充通过；修正测试拥有资源清单后，第四次完成上述最终验收。没有放宽业务断言、改生产权限或强制工具步骤。首次新实现的默认 glob 空模式及测试夹具／格式／静态失败同样保留，最终已修复，不删除失败证据。

原始结果在忽略的 `build/native-search-2026-10-26/`：完整主机 `final-debug-gate.log`、测试汇总 `test-totals.json`、最终双渠道验证构建 `final-validation-build.log`／`cleanup-test-build.log`、最终 Advanced `knowledge-cleanup-fixed/` 与 Standard `standard-native/`。运行器记录每份实际安装 APK 哈希；只收取合成断言事实，不收集凭据或用户日志。全部自建实例退出，测试账号退出；日常实例未因后续验收再次清理／覆盖。

边界：真实 SAF Provider 的撤权／遍历耗时、模型自主选择 glob／grep、真机／其他 OEM、大目录实际时延以及本轮 Release/R8 未验收。Document Backend 已有 JVM 同契约夹具，不冒充真实系统 Provider 验收；新搜索不依赖真实服务，知识闭环单样本成功不证明泛化质量。正式发布产物不变。

## 最终日常包更新

所有者接续安装请求后，将 `3c682f6b` / `5a7286be` / `acbdef6b` 对应的最终源码重新构建为普通 Standard／Advanced Debug 包（非 `.asrvalidation`）。两渠道 `assembleConsumerDebug` / `assembleDeveloperDebug` 成功，沿相同签名、versionName 0.0.7／versionCode 1 和两库 schema 1，在原 Helix_API_36 / emulator-5554 执行 `adb install -r` 覆盖；没有再次清数据、卸载、降 schema 或使用旧清除确认。签名与 UID 保持不变，安装字节与新候选一致；Room 当前身份及聊天／Provider／知识／模型调用等核心表计数保持不变。

两个日常包均实际通过启动、知识／笔记／资料、引用选择取消，以及权限页只读查看 Registry 生成的 glob／grep 行；没有切换工具／权限或发送模型请求。临时 UI 脚本的冷启动／空层级旧快照和子页返回假设已更正，初次失败保留，未将失败包装成通过；最终操作按真实 Back／抽屉路径完成。仅清理本次 UI XML，最后停留在 Advanced 知识库页。安装及烟测证据位于忽略的 `build/native-search-2026-10-26/daily-install/`，其中保存精确签名／哈希／数据计数，不在文档镜像动态清单。正式发布、真机及其他应用均未操作。
