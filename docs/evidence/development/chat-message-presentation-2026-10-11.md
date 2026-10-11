# 过程说明与最终答复展示分层

日期：2026-10-11。Owner要求实施已讨论的文字层级，参考DSH与Codex桌面；范围和主流参考取舍见[HXA-271阶段4](../../development/tasks/HXA-271.md)。本次仅展示投影/原生Compose，不改数据库、Provider协议、执行/授权/预算。

## 实现与边界

依据协调器原子TEXT/TOOL_CALLS提交识别过程；仅持久成功且存在之后可见回答时可折叠。灰色小字及三行预览，整行标题披露；最终、未分类/流式、失败/取消/恢复/未知状态保留原展示。交互提问Turn保守不弱化，审批/错误/恢复独立。搜索命中展开，原文/消息身份/时间线顺序不改变。复制/分享/分叉/重新生成沿用原消息路径。

较长定义是240字符或4换行的显示阈值，不涉及token额度。不能凭模型措辞推断“最终”或重要性；未确认消息继续完整正常显示。本次不宣称完整Responses phase兼容，亦不推断闭源桌面实现。原始日志和制品位于ignored `build/chat-presentation-20261011/`，不含真实模型输入或账号数据。

## 验证

主机入口：

```sh
./gradlew spotlessApply
./gradlew spotlessCheck detekt test \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleDeveloperDebugAndroidTest -PincludeSpikes=true
./scripts/check-all.sh --source
```

新增MessagePresentationPolicyTest覆盖成功/运行/失败/取消/恢复/状态损坏、问题/系统节点、交叉Turn/失效记录、缺少最终可见回答、重开乱序、原文和身份保持。新增MessagePresentationDeviceTest准备折叠/展开、字号、搜索自动展开及披露不触发消息动作场景；仅编译不算设备通过。

初次host-r1因格式化不可自动修复长行及分类函数复杂度失败，拆开边界与展示决策辅助函数并格式化；host-r2的lint发现新设备测试Composable命名错误，修正为Body，不删除/弱化断言。最终host-r3.log exit 0：全仓JVM、spotless/detekt、双渠道lint/APK及App AndroidTest APK通过，新增纯分类测试每渠道6/6，无skip。source-final.log exit 0：206项主机脚本测试及文档/ADR/I18n/秘密检查通过；日志中模拟的FAIL_FUNCTIONAL属于解析器负例，不是设备执行。

四项新UI设备夹具已编译，尚未执行；设备/真实模型状态not requested，本轮未启动或使用模拟器/真机，未调用模型，未覆盖安装日常或手机。窄屏/字体/触摸/滚动及真实模型显示效果仍需下一次当轮授权验证，不能用前一天设备通过替代。既有无关在途改动保留，不整体暂存或提交；本地候选制品不能宣称纯净HEAD。
