# HXA-275：组件诊断与作者样例验证

日期：2026-10-11。范围：[任务](../../development/tasks/HXA-275.md)、[作者指南](../../development/plugin-authoring.md)、[ADR-CONNECTORS-003](../../adr/connectors/003-ownership-and-installation.md)。Owner明确要求完成272～275，允许自有模拟器、现有Qwen和分阶段本地提交；未授权本任务更新日常APK、操作真机或真实第三方账号。

## 实现与采用的设计

根 `plugin.json` 的 Agent Plugins 1.0.0 标准入口与既有外部入口均审计不支持组件声明/默认目录及默认LSP文件。此前标准入口可以保留有效Skill/MCP，但未完整报告同包hooks/Agent/LSP等执行限制；现在明确展示。这里“标准包”不是Standard应用渠道。

`PluginDiagnostics`是包支持事实的唯一结构化投影：类型、逻辑路径、状态、现有操作、是否省略和必要/可选/运行时条件。生产预览、安装返回、readiness、模型和UI共享结果；安装工具输出契约升为版本2，输出schema同步替换。保留内部审计原始事实供归属/存储，不保留旧模型返回格式或UI字符串前缀推断。PACKAGE_SUPPORT的AVAILABLE不表示已连接、选中或授权；HXA-274实时事实和原Dispatcher继续决定请求准入。

Skill不支持的context/agent/hooks字段明确标为元数据，当前执行读取仍按273拒绝。allowed-tools/model不授予权限或改变路由。必要/可选声明只诊断，不求解、下载或创建server；optional对象不是宣称Claude的标准依赖字段。配置schema不生成外部宿主表单；stdio/桌面安装脚本不执行。标准入口的既有可隔离失败与外部Skill的完整校验拒绝、安全归档整体拒绝均保留。

参考[Claude清单](https://code.claude.com/docs/en/plugins/manifest-reference)和[排错](https://code.claude.com/docs/en/plugins/troubleshooting)区分声明、加载和组件失败；采用支持范围/修复事实，保留Android权限、账号与资源归属，不复制参考实现或扩展hooks/LSP/子Agent引擎。作者指南列出实际格式子集、三类样例及更新/卸载归属。

三类精确资源fixture由生产解析器验证；设备使用唯一命名受控实例走生产安装/选择/Dispatcher。native示例来自APK自有Chat Reply：其零工具工作流通过skills.read调用，外部同名runtime导入仍明确省略，不宣称测试了Mobile Use系统后端或新增native执行器。MCP使用既有InAppMcpServer实际loopback JSON-RPC，明确配置本地网络范围并走原审批路径，恢复原profile/scope；被动readiness不产生请求。诊断详情默认折叠，仅显示展开，无启用/授权副作用；设置和系统权限导航使用独立UI回调。

## 当前主机验证

完整命令：

```sh
./gradlew spotlessCheck detekt test \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest \
  -PincludeSpikes=true
./scripts/check-all.sh --source
```

完整Gradle最终执行exit0，45秒；日志`build/hxa275-host-final.log`。组件诊断6项、精确作者样例3项通过（无skip），其余既有安全/归属/取消/安装测试保留并运行。source初次及最终文档更新后的执行均exit0，206个host工具夹具、文档/ADR/国际化/secret检查通过；最终日志`build/hxa275-source-final.log`。

## 当前设备与模型验证

所有实例使用既有owned runner、只读自有API36 AVD，不保存快照；冻结APK/测试APK SHA256并记录owner/closed。硬件键盘与硬件键盘下IME按runner设置；本任务不验收拼音候选或其他IME。

| 渠道/设备 | 当前检查 | 结果与证据 |
| --- | --- | --- |
| Developer / API36 arm64 / 自有实例5600 | 作者3项、部分可用/原生/纯内容3项、安装hash/Plan/取消1项、真实Qwen可信输入1项 | passed，8/8，0skip，22.184秒；`build/hxa275-device-final/`，owner进程96789已关闭 |
| Standard(Consumer) / API36 / 640dpi、font1.5 | 支持范围披露2项、配置解释3项、会话选择5项、readiness3项 | passed，13/13，0skip，29.32秒；`build/hxa275-consumer-final/`，owner进程98108已关闭 |

Qwen使用Owner既有keyless本机30008服务和Qwen3.8-27B，当前models探测成功，原有真实模型旅程输出`USER_SKILL_MODEL=PASS`。没有调用其他账户、隧道凭据或发送真实第三方消息。

Developer最终应用SHA256：`b59dcca32ed5800fad72f32ff649185a564944c25eeb71864b5aa130a436a8aa`；测试APK：`b3f8d9feed6ebb20adcd7dc9ed6ce46a9c141e882df5d9af2fa82985f3b391b0`。runner精确参数保留在受控执行记录/现有脚本入口；`--reverse-port 30008 --instrument-arg pluginSkillPort=30008 --instrument-arg pluginSkillModel=Qwen3.8-27B`只作用自有实例。

Standard最终应用SHA256：`81f407d129128daa48357d2e8f7efb8f4e75df2e555814a2523db4cdb5ab78e2`；测试APK：`cf3e23bfc13969e0a27252d49bca5c3d924ef89fdfdae33b82698ace07e0c628`。窄屏实际270dp宽，使用原中文资源；无真实模型或系统权限操作。

## 失败与复验

- 初次编译失败：输出schema常量在引用常量后声明；移到使用之前，编译通过。格式器发现长行，多次修正至通过，不跳过格式检查。
- 初次完整主机检查失败：UI导航回调拆分后参数静态门槛、组件审计复合条件。仅该UI函数注明参数原因并局部豁免；审计条件拆成命名事实。完整重跑通过，无全局门槛调整。
- 初次Developer8项中7项通过，MCP样例漏了真实fixture schema要求的case字段，被INVALID_ARGUMENTS正常拒绝。补正确case并在停用后复用同样参数；最终8/8通过，网络调用计数保持不变。保留`build/hxa275-device1/`失败轨迹和关闭事实，不修改生产校验或服务schema。
- source工具回归含故意失败的嵌套instrumentation样本，输出FAIL_FUNCTIONAL用于测试失败识别；其206项JUnit及最终source命令exit0，不把该负例当作真实设备失败或成功。

## 验证边界

不由本轮结果关闭真机黑屏/OEM/长稳、真实OAuth或账号、完整进程死亡/更新矩阵。安装边界测试是实际Dispatcher和审批，但没有新增强杀设备旅程；继承的存储恢复主机测试不冒充本轮真实进程死亡。Standard没有运行需要Advanced的loopback MCP，窄屏UI验收单列。日常APK和用户数据未覆盖；Browser贡献/资源释放/装载收益/新观察工具仍归276～279，未在本任务实现。
