# 插件市场设备与真实模型补验

Owner本轮明确要求设备及真实模型验收，随后指定自有模拟器与连接的OnePlus都测；使用既有本机Qwen3.8-27B、合成插件/文件任务，不使用第三方真实账号。延续HXA-281已交付主机实现，不增加执行域、账号迁移、App UI或自动下载安装服务。

## 验收路径

1. 导入Claude市场索引及目录内插件包，验证目录添加不安装；安装取消不提交；安装、会话选择和目录删除各自独立，删除目录保留安装及会话选择。
2. 通过真实Compose市场界面预览、确认版本2.0安装、展开详情并进入会话，核对回调携带准确安装ID。中英文320dp/大字体搜索、筛选及实际拼音候选/中文提交独立验证。
3. Developer及真机：市场内Codex格式Skill/MCP包走原安装器，显式启用内容、配置真实loopback JSON-RPC握手、选择会话；真实Qwen读取Skill、发现并调用当前Helix MCP名称，最终文本包含工具真实返回的SYNTHETIC_READ_OK。另有原Dispatcher纯Skill、MCP首次审批与取消选择后网络零调用检查。
4. Standard不能切换Advanced或访问loopback MCP，按既有ADR边界验证市场与独立导入Claude格式Skill；真实Qwen读取合成CSV，尝试缺失主输入后用授权备用输入恢复、写出并读回JSON，独立校验East=140、West=100、total=240。不是放宽LAN策略来让夹具通过。

## 当前结果

OnePlus6T/API34 Developer最终5/5，0失败/跳过。真实模型轨迹为tools.search、skills.list、skills.read、tools.search和当前mcp工具，5次调用全部COMPLETED，最终回答标记断言通过。设备安装前后及最终清理后，原8会话、10个Turn和5个Provider逐行hash一致，Room identity一致、integrity=ok，无在途Turn。原包/数据库仅存ignored build私有目录，没有重建数据库。临时provider、合成插件/会话和本轮端口映射已清理；没有修改无障碍、Root、Shizuku或悬浮窗系统权限。

Developer/API36第二轮业务7项通过（包括真实Qwen4次工具调用、标记校验及拼音），仅2项异步UI夹具失败；最终定向UI复测2/2、0失败/跳过，退出0。合并对应方法的最新结果，9个不同方法均通过，不将原9项批次写成全绿。Standard/API36最终组合6/6、0失败/跳过、退出0；Claude格式文件Skill真实Qwen9次调用完成，缺失输入恢复和实际JSON读回独立校验通过。两渠道拼音候选及中文提交均通过，closed.json确认自有进程正常关闭。

## 失败尝试与夹具修正

- emulator-baseline的6项中2项UI失败：新文件选择器要求ActivityResultRegistryOwner，但本地化夹具用Application配置context覆盖后未传递Activity宿主。保留原失败，补传真实owner；同时更新旧市场标题断言。
- 新界面用例首轮安装后等不到会话按钮：按钮位于展开详情，夹具补上真实展开操作，不移除确认/安装身份断言。
- 首轮模型夹具遗漏Skill显式启用，Qwen真实尝试读取后停在启用审批。超时清理立即删除会话又早于后台取消结算，导致TURN_NOT_FOUND和测试进程崩溃。改为显式启用合成内容、等待AgentRuntime观察完成并核对持久终态后再删除；不是吞异常或放宽取消/权限语义。真机遗留的唯一合成Provider经原生产删除接口清理，原用户记录均保留。
- Standard首候选错误请求Advanced，被既有渠道边界拒绝；下一候选误选Developer专属chat-reply，真实返回PLUGIN_NOT_INSTALLED。改用独立导入的Claude格式文件Skill，不隐藏渠道失败或新增隐蔽开关。
- Developer第二轮业务7项通过，但2项UI在异步目录读取完成前断言条目不存在。增加对实际条目就绪的10秒有界等待，之后仅复测失败UI，未重复已通过的模型任务。
- 新模型夹具复杂度门禁失败后拆出验证/清理函数，未修改阈值、删除断言或跳过测试。原失败和修正制品保留在ignored build/hxa281-acceptance。

## 命令与制品

最终完整主机`./scripts/check-all.sh --all`退出0（full-host-r3.txt），source/JVM、spotless/detekt、双渠道Debug/Release lint/APK、40份依赖锁和打包/运行时边界均通过。既有WorkBuddy样本通过opt-in环境参数实际复验；两渠道AndroidTest编译入口为`:app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest`，最终compile-r11退出0。此前full-host与full-host-final也退出0，但最终结算采用全部夹具修正后的fresh门禁。

设备原始结果：phone/run-r2、emulator-developer-r2及emulator-developer-ui-final、emulator-consumer-final，均位于ignored build/hxa281-acceptance。主机退出码为0的设备runner逐方法核验通过；原失败目录baseline/developer-r1/r2、consumer-r1/r2、phone/run保留。Developer第二轮退出1对应旧UI失败，即使该轮业务通过也不改写批次退出码。

模拟器使用`scripts/run-owned-emulator.py --raw-results`、自有只读API36实例、两个独立端口及独立输出目录，未覆盖日常APK/数据；每次记录APK hash、设备属性、逐方法状态、日志和closed.json。所有授权启动均保留主机键盘与可用IME，拼音候选/实际中文提交由PinyinComposerDeviceTest验证。真机定向am instrument逐方法由instrumentation_junit.require_passed核验；只有真实终态为0且runner成功结束才记通过，不从测试总数推导。

当前Developer主APK SHA256：313cea3b75cdf19a09ccac4c18aeec4c40ccbb4422af56ce222ee30f1080ebaa；Standard主APK：e5656ec74a3595e5c96b922f8d9d15c322689d45948fe84397e5166ef91ecdc1。每次冻结制品详情以各run的artifacts.json为准；不将测试APK编译等同设备通过。

## 验证边界

本次是合成场景的设备/模型功能验收，不是误调用率、成本收益或普遍成功率实验。没有真实第三方OAuth账号、公网MCP服务、MCP App页面、stdio/hooks/LSP/桌面运行时、其他OEM或长期资源验收。WorkBuddy原样本保留主机解析/资源校验范围，不宣称其真实账号服务已连接。手机logcat包含历史条目，崩溃归因按本轮测试PID/时间核对，不把旧系统FATAL当当前故障。
