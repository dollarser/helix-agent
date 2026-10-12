# Mobile Use 后端、隐藏确认及恢复补验

日期：2026-10-12。HXA-271阶段11。Owner本轮授权同一OnePlus/API34、现有Qwen、临时无障碍/浮窗、已有Root及最长两小时稳定性检查，随后允许安装Shizuku和必要的数据重建/账号操作。没有使用日常模拟器或因测试便利清库。原始日志/合成截图在ignored目录`build/mobile-followup-20261012/`，不提交设备标识、凭据及用户数据。

## 实现与边界

呈现包装器在准入前、隐藏失败后及执行前检查取消/deadline，返回原生Cancelled/TimedOut；真正的隐藏失败保留OVERLAY_HIDE_NOT_CONFIRMED和无动作派发事实，提示观察到恢复后才重试，不能循环相同请求或绕过隐藏。进入执行器后的结果不改写。

首次移除、两帧及View脱离确认后，同一连续隐藏租约组可复用确认，嵌套截图/手势不重复等帧。最后租约退出/关闭清除确认；旧帧回调必须匹配仍存活的原ticket，不能授权新组。取消不能早于真实Android回调释放物理资源。[此前AOSP与agent-device对照](overlay-observation-retention-2026-10-12.md)保持；不承诺所有OEM合成器完成。新增主机回归覆盖复用、晚回调、隐藏中取消/期限和失败零派发。SDK最终9/9、15.733秒通过，四次嵌套确认约0.50–0.76毫秒，仅记录测量，不设固定性能计数门禁。

## 当前同机验证

设备OnePlus A6013、Android14/API34、Developer。更新前保存安装APK/Room库，identity=`eb439f0c60ced11912eaac8b50dada5b`；没有卸载、清数据或重建库。现有Root按本次显式请求连接，夹具退出断开。

Shizuku从[官方13.6.0发布源](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)安装，APK版本13.6.0.r1086.2650830c；Android签名校验成功，本地下载SHA256=`6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`。发布API没有独立digest，本地hash不是官方独立校验。ADB启动的serverUid=2000，实际系统交互临时授权Helix；没有开启自动启动。

| 当前执行 | 结果与范围 |
| --- | --- |
| 无障碍Qwen表单 | 两轮2/2，中文输入/滚动/单次点击及独立TEST_DONE/count=1；四次HOME/返回4/4，原会话/AX连接保持 |
| Root Qwen表单 | 两轮2/2，实际工具结果包含root后端 |
| Shizuku Qwen表单 | 两轮2/2，实际工具结果包含shizuku后端，服务UID2000 |
| Root短按及权限边界 | 方法1/1、三次DOWN/UP为86/125/77毫秒，clicks=3；预取消/只读/旧范围/越界拒绝 |
| Shizuku短按及权限边界 | 方法1/1、三次独立短按及clicks=3，取消和范围边界保持 |
| 返回像素正反例 | 2/2，亮/暗主题标题通过，均匀黑/白和无交集区域失败 |
| Qwen实际截图/坐标动作 | 两轮2/2，实际ui.device→ui.screenshot（pixelsAttached=true）→ui.gesture及独立count=1；四次后台返回4/4 |

现有localhost:30008 Qwen3.8-27B，每后端固定两轮、240秒轮次上限，不读取或展示API Key。完整instrumentation状态由严格解析器核实；仅COMPLETED不足以通过，另外核验实际中文和恰好一次点击。

## 失败尝试

无障碍首轮业务成功22.120秒，旧后台检查却因稀疏颜色仅五种而失败；保存截图显示完整会话。改为预期标题bounds内渲染对比，保留会话和AX检查，补黑/白失败正反例后，两轮/四次返回通过。历史黑屏继续按Owner规则关闭，不将启发式失败当新黑屏。

采集日志的带空格时间参数被ADB拆分，采集失败；原instrumentation两轮事实保留，补采后改用有界条数。Shizuku首轮AX重绑定准备超时，未执行动作；等待本轮instrumentation开始，避免旧焦点后原断言通过。进程恢复首轮因写死模拟器10.0.2.2连接失败，未到点击；abort清理后增加受限loopback测试参数/临时ADB reverse再验，不改生产Provider或持久授权。

恢复夹具另外暴露冷启动直接调用未发现工具，以及ActionResultHold修改原生implementationRevision使插件未就绪的问题；前者改为两次精确tools.search及真实snapshot/click，后者只暂停测试进程的结算而保留实际生产实现标识。注册仍产生新incarnation，不允许旧绑定准入。新增三项主机夹具回归确认冷启动先发现、保留原token及拒绝把失败观察当节点。模型调用次数不再写死，记录杀进程前实际事实并在恢复后精确比对；原动作序列、取消/身份、未知效果/审查和用量结算断言保留。

两种实际SIGKILL恢复已通过：已落库动作/模型回填等待与动作已发生/结果未结算。各自recover、recover-final两次重开通过，独立持久clicks始终为1；未结算调用INTERRUPTED、Goal BLOCKED(NEEDS_REVIEW)，已落库调用保持COMPLETED且不重放。当前阶段没有以coroutine取消替代进程死亡；测试使用合成本机HTTP夹具，和前述真实Qwen分别记录。

两小时应用资源检查正在执行，完成后追加实际结果。其他OEM及24小时不能推导通过。手机当前未安装微信，未指定另一可核验账号任务，不记录第三方真实账号业务通过。

## 主机与安装身份

`host-final-r5.txt`记录全仓test、格式、detekt、双渠道Debug lint/APK、双渠道App AndroidTest及SDK AndroidTest编译；source另记录统一212项主机夹具和文档/ADR/i18n/secret检查。呈现Executor JVM10项、OverlayState14项均无失败/跳过。中途可空正文编译、方法长度与嵌套/函数数静态失败均修正，门禁保留。

实际业务与恢复使用主APK SHA256=`8102cc38e48eae8903a07389e9b9b771542614b1992286304b7a24d681f4f916`；测试APK随夹具修改有不同身份，分别保留在各result/manifest，不混称同一制品。启动长稳前再次备份并记录手机实际APK、Room identity和完整性。Root已断开、AX组件null/开关0、浮窗AppOps恢复default、临时reverse移除；Shizuku实际应用管理页中Helix授权开关恢复false，临时server停止，没有开启自启动。保留Owner授权新安装的Shizuku包。
