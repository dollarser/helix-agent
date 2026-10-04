# HXA-247 文件交接主机验证

日期：2026-10-04
范围：[HXA-247](../../development/tasks/HXA-247.md)，独立文件发现、附件/目录交接、成果定位与交付。主机验收通过，设备 not requested。基于本地 `53bb00f6` 工作树增量，保留原有未提交改动；不是该提交本身或已发布包的能力声明。

## 当前实现

文件首页保留稳定位置，新增有界最近打开/收藏；位置只保存 scoped 引用，元数据持久化成功后发布。增加当前/新会话附件入口、Workspace/SAF 目录任务入口。目录先验证再建草稿；目标会话改变或调用取消后不转投另一会话。保留原文件名，复用原附件分类/导入，不自动发送。

会话成果增加文件管理器定位和交付操作，任务/成果共用原路径定位；完整文件分享与预览文本分享分开。项目页复用同一文件交接组件，目录不自动推断项目成员关系。抽屉仍按会话、项目、模型、扩展、终端、工作、设置排列。

## 验证命令与结果

执行命令：

```bash
./gradlew :app:assembleDeveloperDebug :app:assembleConsumerDebug \
  :app:testDeveloperDebugUnitTest --tests 'com.helix.app.files.*' \
  --tests 'com.helix.app.chat.ChatDraftStoreTest' \
  --tests 'com.helix.app.chat.SessionDraftTest' \
  --tests 'com.helix.app.ui.SettingsNavigationTest' \
  :app:compileDeveloperDebugAndroidTestKotlin \
  :app:compileConsumerDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

最终 Gradle 联合检查通过（41 秒）；11 个 JVM 类共 115 项，0 失败、0 错误、0 跳过。其中新增 FileLibraryTest 9 项覆盖重开、同名异 scope、最近淘汰、收藏上限、写入失败、损坏/未知版本、失效记录移除、取消收藏和并发更新；其余为文件操作、SAF、Root、传输/恢复、草稿与设置导航回归。统计来自本次 XML 报告，不把设备编译计入通过数。

新增 FileConversationHandoffDeviceTest 三项准备当前/新会话附件（含中文显示名与不发送）、切换会话的陈旧目标拒绝、目录失败保留原会话；两渠道 Kotlin 编译通过，实际执行 not requested。

格式、detekt、文档与 diff 检查通过。原始本机日志在 ignored `build/file-workflow-final.log`、`build/file-workflow-docs.log`；测试报告在 `app/build/test-results/testDeveloperDebugUnitTest/`。

| Debug APK | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/developer/debug/app-developer-debug.apk` | `cfefefe25dc2bc1951f276be306205c8e0cec40aef88101f3ec84b5d2fb002f2` |
| `app/build/outputs/apk/consumer/debug/app-consumer-debug.apk` | `1cbade5f27908f773fbbb77cf7e1d1dc629e60ddd6e24bac964ea9f816204d91` |


## 失败修复

- 初次记录存储测试暴露 app 模块没有启用生成序列化器。改用项目已有 JSON 基础能力显式编码版本与字段，不增加编译插件或依赖。
- 目录入口最初先建草稿再验证，失败可能替换原会话。改为结构化挂起调用，先验证、再在主线程复核原会话身份并创建，不让界面离开后的异步结果改选会话。
- 交接的最终身份判定下移到 ChatService 实际选中身份，避免 UI 投影尚未刷新时创建错误会话；原 stageAttachment 继续检查预期会话。
- 静态检查指出组合函数长度/路由判断复杂度与格式问题，提取文件界面状态、交付状态和固定路由标题表；没有关闭检查。

## 未验证范围

未启动/使用模拟器或真机，未调用真实模型/账号，未安装、提交、推送或发布。设备回归仅编译。最近记录并非全盘索引或全部生成历史；没有实现全局文件修改快照与整轮恢复。SAF/Root 的运输副本、大小限制与权限仍由原后端约束，主机通过不能证明所有文件提供者行为。
