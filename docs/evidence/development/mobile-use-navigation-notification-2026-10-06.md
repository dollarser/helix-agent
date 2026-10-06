# Mobile Use 独立导航和通知弹窗回归

日期：2026-10-06。所有者授权本轮有限模拟器回归及一次真实模型任务，并补充酷安原先停在通知授权弹窗、当前已不再显示。没有清除酷安数据或重置其权限；使用测试 APK 的同类 Android 通知弹窗复现。

## 原任务诊断补充

[此前记录](session-extensions-and-douyin-diagnosis-2026-10-06.md)中最新安装任务实际使用 Shizuku。其最后一次模型请求包含 38 个工具，包括 ui.screenshot/ui.device/ui.gesture，并非截图工具被注入裁掉。持久状态为 COMPLETED、无错误；模型仅返回“截图查看”的最终文本，没有相应工具调用。现有记录不能还原未保存的原始服务端 finish_reason，也不能断言模型内部动机；没有安装成功证据。

用户补充解释了受保护页面的用途。合成复现确认 Android 通知弹窗的敏感标记覆盖容器与公开按钮，旧实现提前结束遍历，模型看不到任何选项。修复允许遍历非密码容器，并仅对系统确认的 PermissionController 的公开说明和选择控件开放语义。Google 组件的实际包名与 AOSP 资源命名空间不同，必须分别校验。允许和拒绝同时暴露，harness 不决定答案。

## 实现与回归中的修复

- ui.launch、ui.back、ui.home、ui.system 接入封闭高权限后端，仍按原调用授权及实际操作就绪能力选择，不在未知结果后重放。
- 初次设备回归发现隐式 Launcher Intent 无法解析 Helix 入口，改为 PackageManager 解析显式 component；动画期间窗口变化被拒绝，测试在界面稳定后继续，未取消目标校验。
- 测试 APK 独立进程缺少目标 APK Kotlin runtime，新增权限回调触发 Intrinsics 类缺失；回调移至无 Kotlin 运行时依赖的 Java 测试 Activity 基类。
- 工具反馈与内置技能解释新观察失效旧 token、受保护节点和最终文本的执行含义。没有硬编码通知答案、固定业务流程或强制关键词续跑。

## 当前设备与真实模型证据

设备为 emulator-5554 / API36 arm64 / Developer。全程无障碍关闭，实际后端 Shizuku。Root 共用协议和执行实现及主机选择测试，不宣称本轮 Root 设备通过。酷安真实首次通知页面未重新触发，抖音下载安装全流程未重跑。

1. `PrivilegedNavigationDeviceTest`：1/1 通过，验证高权限启动、主页、最近任务、返回、读取通知选项、点击拒绝及独立权限回调结果。最终 APK 再验通过，日志 `build/douyin-diagnosis-2026-10-06/navigation-device-final.log`；此前失败保留在同目录。
2. 当前 Qwen3.8-27B **一次任务**：在同类弹窗选择“不允许”（测试用户明确要求），填写“你好 Helix”，滚动并点击完成一次，最后核实结果。16,162 ms，10 次模型请求、9 次工具调用、0 次审批、0 次 UNKNOWN；独立 oracle 核对通知回调为拒绝、完成文本正确且点击恰好一次。通过不意味着每次模型都会选择拒绝，也不证明所有任务的提前结束问题消失。
3. 命令：`scripts/debug/2026-10-05/run-capability-eval.py shizuku-notification-20261006 notification --allow-real-model --without-accessibility`；provider/model 由当前本地配置指定，没有输出凭据。默认五项评测集合不变，新模式不会开启无障碍。
4. 真实模型证据保留在忽略目录 `build/model-capability-eval/shizuku-notification-20261006-*`。测试恢复 Mobile Use 全局配置和原会话；只重置测试 APK 的通知权限，没有修改酷安数据。

主机：Mobile Use 206 项 JVM 测试全部通过；detekt、spotlessCheck、Consumer/Developer Debug 构建和 Developer AndroidTest APK 构建通过（`host-final-r2.log`）。Developer Android lint 通过（`lint-final.log`）。`bash scripts/check-all.sh --source`、`bash scripts/check-docs.sh`、`git diff --check` 通过。设备通过不代表 OEM/真机或完整发布验收。
