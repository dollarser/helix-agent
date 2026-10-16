# 插件诊断与外部样本验收（2026-10-16）

范围：[HXA-299](../../development/tasks/HXA-299.md)。Owner 明确按建议实施，允许联网、日常模拟器与真实模型；不启动完整架构重组，不推送发布。起点 `main` / `6bbff42d`，162 个既有修改文件已在忽略的 `build/hxa299/baseline-tree/` 保存，既有工作不重置。竞品取舍见[插件研究追加章节](../../research/topics/plugin-guidance-and-management-2026-10-16.md#后续授权的诊断切片hxa-299)。

## 交付及边界

1. **样本入口**：`scripts/local/fixtures/connectors/` 集中保存原始 QwenWork、WorkBuddy GitHub/可灵两个市场包与六份固定公开配置。本地 manifest 记录来源/哈希；公开文件与既有固定值一致，重新下载也通过校验。原件和旧证据保留，未执行包内脚本、导入账号态或连接这些包的业务服务。维护说明在 [scripts/fixtures/connectors](../../../scripts/fixtures/connectors/README.md)，准备与运行通过 `scripts/connector-samples.py`，不受 Gradle clean 影响。
2. **独立门禁**：三个离线输入不再隐式启用真实联网 MCP；网络单独要求 `HELIX_PUBLIC_MCP_ACCEPTANCE=true`。运行器拒绝错误哈希、空/跳过/失败报告、重复输出目录，强制重跑选定测试任务并复制当次 XML。
3. **上下文诊断**：注册的提示段只求值一次，同一 PromptSnapshot 记录来源、hash、是否加入/省略原因、UTF-8 字节和估算文本 token，随模型调用及审计持久化。原模型文本与拼接顺序保持；段内估算不含消息框架或图片/工具 schema，不能求和冒充 Provider 实际计费 token。实际 usage 仍独立保留；env.plugins 仍为聚合段，完整子贡献/预算诊断归 HXA-293。
4. **插件测量与局部优化**：注册、就绪、指纹的次数、异常次数、累计/最大耗时采用单调时钟、有界单进程内存统计，通过已有诊断导出读取；不存正文、异常消息或凭据，不决定权限和持久状态。计时包含嵌套操作，不能叠加当总启动耗时，也不包含构造器外部工作。会话上下文先检查持久选择，未选插件不探测 runtime；已选项仍实时重验，不新增权限/就绪缓存。
5. **模型实测发现的请求失败**：`ask-user.md` 多段换行直接进入严格工具描述，导致请求装配报控制字符并终止为 INTERNAL。`UserQuestionTool` 现将该内置模板段落合并为单行；补实际 FileToolArguments → ModelToolSchema → 展示元数据回归及模拟器全注册工具 schema 检查。没有放宽外部 schema 校验或删除提示约束。

## 当前验证记录

所有原始日志/命令/设备结果位于忽略的 `build/hxa299/`；记录中的通过只限列明范围。

| 层次 | 命令或用例 | 本轮结果 |
| --- | --- | --- |
| Python 运行器契约 | `python3 -m unittest discover -s scripts/tests -p test_connector_samples.py` | 6/6；不访问网络/ADB/private fixtures |
| 离线真实输入 | `python3 scripts/connector-samples.py test offline --output build/hxa299/offline-final` | consumer 3/3、developer 3/3，0 skip |
| 真实匿名 MCP | `python3 scripts/connector-samples.py test public-mcp --output build/hxa299/public-final` | consumer 1/1、developer 1/1，真实握手/metadata/query，0 skip |
| 最终主机门禁 | `./scripts/check-all.sh --all` | `check-all-final.log` exit 0；275 Python、全部适用 lint/JVM/Debug/Release/锁及制品边界通过 |
| 关键 JVM | Agent / Plugin / App 两渠道 | Agent 293/293；Plugin 85/85；Consumer 1254 中 1250 pass/4 skip；Developer 1375 中 1371 pass/4 skip。4 项可选输入/网络测试已由上方独立专项实际通过 |
| 静态/制品 | 两渠道 Debug 主 APK/AndroidTest APK、spotless/detekt | 构建及执行通过；Release smoke APK 仅编译 |
| API36 Standard Debug | QwenWork、WorkBuddy、诊断及 PluginManagement | `consumer-samples-ui` 7/7 |
| API36 Developer Debug | 选择诊断及样本/管理 UI | `developer-selection-final` 1/1；`developer-final-regression` 中导入/UI 6/6，另 1 项模型超时，因此不称该批 7/7 |
| 真实 Qwen3.8-27B | Standard 文件工作流 | `consumer-model-final` 1/1；发现/读取 Skill、缺失输入恢复、工具读写与独立核验，诊断字段实际持久化 |
| 真实 Qwen3.8-27B | Developer 文件与原生指导 | `developer-model-final` 原生指导 1/1；恢复后 `developer-model-after-recovery` 文件工作流 1/1（约 35 秒）。此前 SERVER_ERROR/TIMEOUT 原失败保留，不将原批次改成全通过 |

设备为 Owner 指定日常 `emulator-5554` / `Helix_API_36` / API36 / ARM64；无真机。模拟器是 Debug 本地验收，Release/R8 本轮只构建，不复用为 R8 实测通过。实际模型沿用 Owner 提供的接口，经短时 loopback 转发保持凭据只在进程内存；合成测试工作区和原生指导为本轮数据范围，不调用 WorkBuddy/QwenWork 账号业务。

## 测量与失败事实

Owner 后续说明，中途网络异常发生在其切换网络期间。这是用户补充的环境背景，网络失败不单独列为待修 Helix 缺陷；保留以下原失败批次与成功复测。该说明不替代对同时观测到的模拟器控制台/shell 无响应的记录，也不据此认定两者具有同一根因。

- `developer-unselected-baseline` 与 `developer-selection-final` 各执行 20 次未选插件上下文调用。前者约 18–68 ms，后者约 1.1–1.7 ms；每插件 READINESS 次数由 21 降到 1（最终显式 ready 检查），确证省去 80 次无关探测。各轮宿主构建负载和暖态不同，这不是严格性能 A/B，不能宣称整体速度或启动性能按比例提升。`PluginService.list()` 的另一个样本只测目录查询，不能当完整管理页或就绪成本。
- 公开 MCP 首次 `public-initial` 发生 socket 超时；分阶段日志追加后复测与最终双渠道通过。保留失败，不增加生产自动重试或随意放宽超时，也不由一次超时推断稳定缺陷。
- 模型列表初次 Python 默认客户端返回 403；沿用既有 Helix 验证客户端标识后得到当前模型列表。真实模型中间一批转发网络异常导致 SERVER_ERROR；保留对应失败，不能当模型完成或回滚。
- `developer-real-model` 首轮 INTERNAL 是已定位的 ask_user 描述换行缺陷，修复后独立工作流通过。新增测试首轮 nullable 诊断服务引用及格式检查失败已修复，没有跳过或弱化断言。
- Developer 最后一次 TIMEOUT 同时出现日常模拟器失去响应：ADB get-state 仍为 device，但控制台 ping 与 shell getprop 均超过 8 秒；完整主机门禁结束后复测仍失败。主机当时有较高构建负载与交换占用，这只能说明环境异常，不能据此断言模型超时的唯一根因。按当轮授权对同一日常 AVD 发送 SIGTERM，进程在初次 20 秒检查后退出；未用 SIGKILL、未 wipe 数据，随后不加载/保存快照冷启动，保留输入法及硬件键盘偏好。健康、恢复及最终运行各自留证。
- 恢复后控制台约 11 ms、shell getprop 约 50 ms，Developer 同一文件方法单独 1/1 通过。结束前健康检查再次通过；失败与恢复说明环境稳定性边界，不把本次冷启动称为 Helix 进程死亡恢复或长期运行验收。

## 接续及清理

测试方法已清理自有模型配置、会话/合成工作区及导入插件；包内脚本未执行。短时模型转发进程已退出，loopback 端口与 ADB reverse 已移除，密钥未落盘。本轮新增的 Standard 主应用已卸载，原有测试包保留；日常 Developer 更新保留，返回正常新会话界面，未留下测试模型配置。外部样本推送文件为空，Gboard 保留，`show_ime_with_hard_keyboard=1`、无障碍服务 null、字体 1.0；未追加拼音专项。日常模拟器恢复后保持运行。

基线 162 个既有改动中，154 个内容逐字未变；8 个相关重叠文件仅加入本轮诊断、就绪短路、测试和文档接续。`DefaultAppContainer` 原共享元数据门闩及此前会话工具接线保留；没有重置、stash、批量格式化无关文件、提交、推送或发布。本轮 30 个改动路径分为：提示装配/问题工具/诊断接线 6 个、插件注册/测量 2 个、JVM/设备回归 9 个、样本运行/准备/说明/测试 4 个、任务/ADR/研究/证据 9 个；完整路径清单在本地 `build/hxa299/owned-paths.txt`。

HXA-286/288/293 的整体贡献/依赖、资源生命周期和选择策略继续按既有顺序；不要求兼容本次内部诊断 DTO。当前授权范围已闭环；QwenWork/WorkBuddy 导入成功不等于其 CLI/账号业务可运行，真实模型这些合成工作流也不覆盖所有 Provider、OEM、Mobile Use 操作、长期运行或正式发布。切网背景、网络失败、模拟器无响应及恢复各自保留，不由最后一次通过抹除，也不将未确诊的环境现象自动升级为产品阻塞。账号/脚本运行的后续条件见[插件研究](../../research/topics/plugin-guidance-and-management-2026-10-16.md#包内账号服务与脚本运行条件)。
