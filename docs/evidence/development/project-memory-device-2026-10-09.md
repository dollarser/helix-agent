# 项目与 Memory：2026-10-09 本地设备收尾

范围：[HXA-246](../../development/tasks/HXA-246.md)、[项目 ADR](../../adr/workspace/005-projects.md)既有本机项目/Markdown Memory 接线。所有者授权模拟器验证；仅使用自有只读隔离 API36 arm64 AVD、合成会话/目录/数据库，不清理日常模拟器数据、不使用真实账号或物理手机。研发参考沿用该 ADR 的项目/会话/真实目录与显式记忆身份关系，没有新增框架或复制竞品源码。

## 复现与修复

完整项目界面旅程创建项目、保留成员会话与目录、打开四个详情视图、归档/恢复、改名与二次确认删除。首轮 JUnit 方法返回值不是 Unit，初始化失败；修正签名后基本旅程通过。扩展四视图时又发现路由更新早于 Room Flow 界面投影，增加等待实际语义节点，保留显示断言。

随后稳定复现实际应用崩溃：项目“文件”页带有指定 Workspace，但 FilesScreenState 初始 sources 只有默认来源；异步 FilesScopeEffects 尚未加载对应来源，FileDirectoryTask 的 currentSource.first 查找抛出 NoSuchElementException。修复在来源确认前呈现加载状态；加载失败显示原错误与重试，来源出现后再显示操作和预览。没有用默认目录冒充原项目目录，也没有新增授权。来源被移除仍走既有回首页/关闭过期操作路径。新增状态回归证明初始目标保持、加载后仍是原 scope/path。

这是模拟器确认的页面崩溃，不证明此前所有者真机卡顿/整屏黑屏原因；没有实体手机连接。

## Memory 验证

既有全局保存/重开/确认删除、外部编辑冲突保护、生产提示读取权限/UNTRUSTED 保留。新增显式项目 Dialog 保存只能落到该 projectId，不改变其他项目或全局文件；两个项目共享同一目录时，其项目摘要和指令仍隔离。首次提示夹具错误地写 user.md，修正为现有注入契约的 memory_summary.md 后全部隔离断言通过；未移除失败断言或扩大自动注入。

真正进程死亡夹具在已开放 Room、Markdown 和设置后执行 Process.killProcess。下一 instrumentation 证明 PID 变化、原 projectId/成员关系、Global/Project 摘要、enabled 设置、项目指令及提示 UNTRUSTED 都保留。只清理隔离 AVD 的测试目录/设置，绝不在日常模拟器执行该夹具。

## 命令与结果

- `build/hxa246-20261009/memory-ui/`：既有 Memory UI/权限 3/3；`memory-process/`：原 Global 进程死亡恢复 1/1。
- `build/hxa248-20261009/background-project-ui-r2/`：J2 八项与基本项目生命周期 UI 合计 9/9。后来新增四视图旅程以各自新运行结果为准。
- `build/hxa246-20261009/project-memory-r2/`：Memory 五项通过、项目 UI 异步投影失败；`project-ui-r3/`：实际 FilesScreen 来源查找崩溃，原始堆栈保留。
- `build/hxa246-20261009/project-memory-r4/`：Developer 六项 6/6（ProjectLifecycleUiDeviceTest 一项、MemoryDialog 三项、MemoryPrompt 两项），主 APK SHA `5f24adb27034fffd116ae95a085c3cdfb6527556fc2c92d28a07c8cdfd82b0e3`。其后仅将返回 Boolean 的 composable 改为小写名以满足 Compose lint，行为不变。
- `build/hxa246-20261009/project-memory-process/`：扩展的 Global/Project 真正进程死亡恢复 1/1；主 APK SHA `ab295143d3566222215de4949095b1aa04f2537b5b116b9b316646e040e39db9`。PID 与 setup-crash、恢复输出在同目录，不把 graceful reopen 当进程死亡。
- `build/hxa246-20261009/consumer-project-memory/`：Consumer 同组六项 6/6，主 APK SHA `e43dadf091477704b821a8e4642e7fdb131f616471929722212ef37a156f876d`。Standard 与 Advanced 共享项目/Memory 产品行为，不由 developer Runtime 能力决定是否提供。
- `build/hxa249-files-final-host-r2.log`：全仓 test/detekt、双渠道 Debug lint、APK/AndroidTest 编译通过（990 tasks，57 executed）。此前 FilesSourcePending 返回 Boolean 的命名 lint 失败已修正，不跳过检查。最终格式/源码门禁另记录。

`build/hxa249-final-static.log` 最终 spotlessCheck/detekt 通过；源码/文档/ADR/i18n/密钥门禁在本轮最终收尾刷新。API29 完整界面/OEM/大字体/完整中文 IME、真实模型 Memory 摘要质量、P5 clean 候选及用户内测没有由本轮关闭。模型协议兼容另由 HXA-249 跟踪，不把 UI 或 scripted 测试当真实模型成功。
