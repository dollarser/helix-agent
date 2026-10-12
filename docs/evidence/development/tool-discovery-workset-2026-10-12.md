# 工具发现与请求工作集优化验证

日期：2026-10-12。HXA-280。Owner授权实现及模拟器/真机/现有模型验证；研究和取舍见[工具专题§12](../../research/topics/tool-exposure-and-discovery-2026-09-29.md#12-2026-10-12-工具架构重新评估与优化设计)。只记录实际切片，不将设计或历史结果当新验收。

## 切片1 取消全局工具数量上限

移除生产与评测组装的固定take、ModelRequest/ModelToolBindings/manifest的全局64计数校验。唯一名称、精确binding、schema校验、现有上下文预检和256KiB manifest字节边界保持；不改数据库或Provider能力快照，不假定任意服务有统一数量上限。

新回归验证160个工具的请求和最后一个绑定、完整manifest及过大绑定清单明确拒绝；曝光排序测试改为完整集合和安全准入断言，不再维护默认工具数门禁。模式/禁用和发现失效断言保留。定向core:model及app曝光/发现JVM通过；首次格式命令误用了不存在的模块spotless任务，改用已有根spotlessKotlinApply后通过。全仓source/host220项、docs/ADR/i18n/secret通过。日志在ignored `build/tool-cap-host-final.txt`、`build/tool-cap-source-r2.txt`。

当前设备和模型尚未执行；全仓JVM/静态与双渠道制品仍在后续整合切片验收，不把定向通过当整项完成。

## 切片2 同源能力目录与检索

目录从本次已准入的descriptor快照投影，提供来源和类别，正文有字节预算并标记截断；不是全量工具正文或另一注册表。tools.search版本2增加source/category过滤、来源与类别输出；索引已有名称/描述、来源、参数字段和宿主维护的常见中文动作词。availability和精确版本过滤保持，未选择/禁用来源不借发现启用。

新增中文截图/归档、字段查询、来源过滤、会话隔离和动态禁用目录回归；原取消测试与ownership测试改为当前注册版本，保留取消和资源身份断言。app MCP定向JVM、spotlessCheck和detekt通过。中途长行、复杂方法及测试写死版本导致失败，分别拆分只读元数据/相关性计算并修正当前契约引用，未放宽静态门禁。证据为ignored `build/tool-discovery-host-r5.txt`、`build/tool-discovery-static-r3.txt`。

已冻结原主APK/test APK，其hash与此前真机长稳制品一致。自有API36/Qwen流程与Browser各两轮业务4/4完成，其中Browser一轮先调用未曝光动作，被正常拒绝后发现恢复；记录全部调用与用量供候选对照，不以4/4推导零无效调用。Mobile Use基线和候选整合仍在后续切片，日常模拟器未操作。
