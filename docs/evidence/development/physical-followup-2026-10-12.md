# 真机补验与黑屏问题结算

2026-10-12，Owner明确要求连接真机测试，并调整HXA-269结算：本轮未复现则关闭黑屏问题。此前主线程阻塞修复保留；不因Owner接受结算而宣称所有历史黑屏均已证明同一原因。

## 设备与数据边界

唯一实体为OnePlus 6T / ONEPLUS A6013，Android14/API34。它的当前系统/安装包与10月10日API35证据不同，不能沿用旧安装身份。已安装Developer为0.1.0、9月24日更新，APK SHA-256 `78f41fb5b2ff52d909f3b406920785edbd5439df7cdf72962a5bd7959cd04732`；Room identity `e2749856a75a203692e582c5336d2348`，1会话/6Turn，备份integrity_check=ok。原APK和DB/WAL/SHM仅在ignored、0700目录及0600私有数据库副本，不进入仓库，不读取配置密钥/用户正文。没有覆盖、卸载或清理Developer数据。

当前Developer候选schema为eb439f0c60ced11912eaac8b50dada5b，直接覆盖将触发开发库重建。按HXA-269“更新主APK前报告开发库风险并取得确认”向Owner询问更新/重建及临时无障碍/悬浮窗、现有Root测试范围，未得到回答前不执行依赖动作。

Standard包此前未安装；本轮安装独立com.helix.agent及其测试APK，合成夹具只在这个独立库运行。生产APK `df979f3e9f040b48405d39d61475076f7c89ad43901d7a4a73043efc579853f8`，测试APK `aade96413c4599d71490b9a5d173ebaaf34d10f88a6fa153a56184cfd153434b`。未使用日常模拟器、真实账号或旧Developer库做fixture。新Standard测试包保留在手机，未擅自卸载。

## 当前实际结果

| 检查 | 实际结果 | 边界 |
| --- | --- | --- |
| Standard组合UI与平台后台 | 20/20 passed，25.725s | 实际HOME后原请求/前台服务完成并返回；配置不足解释/确认/取消3项、会话可见性、工具布局、上下文及过程/最终展示；不是Mobile Use执行 |
| 模型socket取消/断流/旋转 | 4/4 passed，10.56s | 原Goal/请求身份与真实loopback SSE socket，不是Qwen远端故障 |
| 新Standard普通后台返回 | 6次，背景2/2/4/4/8/8秒 | 每次0.3秒及随后2秒截图、焦点和PID；同PID10595、焦点正常；查看实际空会话界面，没有黑屏 |
| 原Developer只读页面返回 | 2次，背景2/4秒 | 同PID11365、焦点正常；实际会话列表截图正常，没有进入用户正文/发送消息，不证明旧包含本次源码修复 |

exit-info没有本轮ANR/FATAL退出；Standard有测试正常完成instrumentation导致的USER REQUESTED/FORCE STOP，不能归类ANR。命令TotalTime及含截图/等待的host耗时不是首帧指标，也不把同PID单独当作渲染通过。原始APK、日志及截图在`build/physical-followup-20261012/`，仅保留此去敏摘要。

结果解析器首次拒绝PhysicalBackgroundRecoveryDeviceTest发出的code2、helix.physical.phase诊断。新增仅接受“一个实际方法正在运行、单独非空阶段字段”的处理，诊断不创建通过/不结算方法；缺方法结束、无活动/多活动、空值及部分身份仍失败。原20个方法的完整开始/终态及runner结束经原入口严格核实；新增host回归9/9通过，没有过滤失败日志或凭OK总数冒充逐项通过。

全仓host/source通过：source入口207项、文档/ADR/i18n/secret；包含可选模块的test、detekt、spotlessCheck、双渠道lint/APK及App测试APK BUILD SUCCESSFUL、2m5s、1117实际任务。Python诊断解析变更不影响手机APK；无生产代码改动。

## 结算与剩余

按Owner新规则，HXA-269黑屏问题关闭：本轮未复现，历史已确认无障碍主线程同步节点等待路径已修复并曾回归。历史视觉黑屏未直接复现，不能证明阻塞是所有黑屏的唯一原因；若出现新现场，按新的实际证据排查，不保留无证据的历史黑屏待办。

Mobile Use临时授权/连接/执行/取消、现有Root和实际Qwen仍pending，等待本轮权限/Developer升级选择；普通UI结果不替代它们。当前Shizuku未安装，未擅自安装或启动新服务。锁屏安全解锁、进程死亡、其他OEM以及2h/24h长稳没有在本轮手机执行；已有模拟器/历史真机结果不升级为本轮通过。手机系统安全设置保持原状，ADB reverse未新增。
