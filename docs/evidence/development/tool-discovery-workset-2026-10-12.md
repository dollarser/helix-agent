# 工具发现与请求工作集优化验证

日期：2026-10-12。HXA-280。Owner授权实现及模拟器/真机/现有模型验证；研究和取舍见[工具专题§12](../../research/topics/tool-exposure-and-discovery-2026-09-29.md#12-2026-10-12-工具架构重新评估与优化设计)。只记录实际切片，不将设计或历史结果当新验收。

## 切片1 取消全局工具数量上限

移除生产与评测组装的固定take、ModelRequest/ModelToolBindings/manifest的全局64计数校验。唯一名称、精确binding、schema校验、现有上下文预检和256KiB manifest字节边界保持；不改数据库或Provider能力快照，不假定任意服务有统一数量上限。

新回归验证160个工具的请求和最后一个绑定、完整manifest及过大绑定清单明确拒绝；曝光排序测试改为完整集合和安全准入断言，不再维护默认工具数门禁。模式/禁用和发现失效断言保留。定向core:model及app曝光/发现JVM通过；首次格式命令误用了不存在的模块spotless任务，改用已有根spotlessKotlinApply后通过。全仓source/host220项、docs/ADR/i18n/secret通过。日志在ignored `build/tool-cap-host-final.txt`、`build/tool-cap-source-r2.txt`。

当前设备和模型尚未执行；全仓JVM/静态与双渠道制品仍在后续整合切片验收，不把定向通过当整项完成。
