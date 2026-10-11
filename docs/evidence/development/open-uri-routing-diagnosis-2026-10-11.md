# android.open_uri：已安装应用与链接处理诊断

日期：2026-10-11；[HXA-271阶段8](../../development/tasks/HXA-271.md)。Owner明确指定检查既有日常 API36 模拟器，若模型理解错误则更新工具描述。本轮只读检查该实例，未启动其他模拟器、运行 instrumentation 或使用真实模型。

## 当前设备事实

- 日常 Helix_API_36，API36 arm64，Developer；Helix 0.0.5，安装时间2026-10-11；已安装APK SHA256为 `d445bd832577c2d8be9aada1f1e16026183a0b5b9dace6dcf405af647d1a5e6a`。只有该模拟器连接，没有实体手机。
- 抖音 `com.ss.android.ugc.aweme` 40.7.0 / 400700，当前用户已安装，Launcher解析为 `.splash.SplashActivity`。
- 使用与工具相同的 ACTION_VIEW + CATEGORY_BROWSABLE 查询 `https://www.douyin.com`，系统解析到 Chrome IntentDispatcher；限定抖音包时无匹配 Activity，添加首页尾部 `/` 后仍无匹配。
- `www.douyin.com` 域名验证为 verified，用户级 link handling allowed 为 true；不能把本例归因于“域名未验证”。该包的相关 Intent filter 有 `/share`、`/redirect`、`/@.*`、`/tag/` 等具体路径，没有匹配所请求的首页。
- 只读合成对照：`https://www.douyin.com/share/video/diagnostic-path` 限定包查询匹配 AppLinkHandler，普通解析返回系统 ResolverActivity。仅证明该路径模式匹配，不打开虚构内容、不证明真实视频页面可用，也不保证普通解析一定选择抖音。

当前前台为 Chrome。复制应用私有数据库/WAL/SHM到 ignored build 目录，主机 readonly SQLite quick_check 为 ok，持久 Turn 全部 COMPLETED，没有在途会话执行；仅查看相关工具调用与答复，没有读取或展示模型密钥。相关记录显示实际调用参数为上述首页网址，结果仅为 status=opened、reason为空；模型却推断已安装抖音一定进入 App。该额外结论并非工具返回的事实。

## 修正与取舍

工具描述明确安装不保证进入 App，完整URL路径、域名批准和用户默认值决定处理器；opened 只表示系统接受启动，不证明实际应用或页面加载。要求观察后再报告成功；若目标是打开应用本身，使用当前可用且已授权的 ui.apps / ui.launch。outputSchema 中 status 的说明同步明确该边界，输出字段、状态值、HTTP(S)限制、执行与授权逻辑不变。

核对[Android 官方 Deep Links 说明](https://developer.android.com/training/app-links/create-deeplinks)：Intent filter 可按路径匹配，Android12+网页链接按域名批准与默认处理规则路由。参考既有 Agent 的结果验证原则，本阶段是现有工具事实说明纠正，无新增执行功能或参考代码复制。

## 验证与边界

设备只读包/域名/完整URL解析及持久调用核对：passed。没有启动抖音、改变默认应用或链接授权、清数据、安装APK；无新 instrumentation 或真实模型调用。私有原始记录保留 ignored `build/open-uri-diagnosis-20261011/`，不提交原始数据库、用户会话文本或完整设备日志。

主机命令为 HXA-271 全仓 spotless/detekt/test、双渠道lint/APK、Developer AndroidTest APK编译、`./scripts/check-all.sh --source` 与 `./scripts/check-docs.sh`。最终全仓Gradle退出0（1m45s），source退出0；AndroidSystemToolsMappingTest 30/30无skip，模块61项通过；主机源码夹具206项、文档814 Markdown/254 HXA、ADR、三语言2242键和secret检查通过。源码负向夹具的 FAIL_FUNCTIONAL 输出不属于设备执行失败。既有映射测试覆盖状态/拒绝/取消与参数；本次只改说明，不新增逐字镜像提示词的测试，也不修改原断言。

初稿先触发格式长行检查，拆分后又被原有ToolDescriptor的1024字符边界及两项描述符回归拒绝；精简下载指导和结果说明后重新完成上述全仓验证。保留原长度限制及所有测试，没有通过放宽门禁解决。本轮同时存在其他插件/ANR在途改动，全仓检查针对当前工作树，不将其纳入本阶段提交或宣称独立验收。

新版描述的真实模型遵循率未测试；模拟器当前安装包仍是之前版本，不将源码及主机构建通过当作已更新设备。既有 Mobile Use 后端未连接问题单独跟踪，本次不能据此声称其已解决。
