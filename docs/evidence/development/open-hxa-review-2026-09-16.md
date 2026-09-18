# 未闭合 HXA 范围复核

日期：2026-09-16。源码 HEAD：`f8c93ca2`，工作区含前序未提交文档重组。本次读取当前源码、ADR、设备测试源码与既有交付记录；未运行Android测试、真实账号、远端CI或发布。本文是审查依据快照，当前执行范围以[任务索引](../../development/roadmap.md)为准。

没有证据支持把27项整体当作“尚未开发”，也没有证据支持将任何整项直接写成完成。过期的是若干旧范围与前置，不是所有未闭合验收义务；因此保留编号，重写责任与类别，不伪造完成记录。

## 逐项处理

| HXA | 分类 | 判断 |
| --- | --- | --- |
| [HXA-094](../../completion-records/HXA-094.md) | 收尾验收 | RootAccessController、libsu依赖与HXA-188已有证据；删除重新做依赖Spike的要求。 |
| [HXA-095](../../completion-records/HXA-095.md) | 收尾验收 | Root工具已存在；与094分工为工具scope/数据/撤权后的实际调用，不重复做连接底座。 |
| [HXA-120](../../development/tasks/HXA-120.md) | 发行队列 | 不是重做flavor；审计选定渠道的最终包与能力，政策在实施时查官方资料。 |
| [HXA-121](../../development/tasks/HXA-121.md) | 发行队列 | 与206/199复用同一候选版本证据；额外负责真机、签名包和发布材料，不重复造产品功能。 |
| [HXA-122](../../development/tasks/HXA-122.md) | 发行队列 | 旧主/companion同签名与安装顺序已过期；仅主应用身份、包内组件、数据升级/降级边界。 |
| [HXA-123](../../development/tasks/HXA-123.md) | 发行队列 | 可先准备材料；提交、审核、通过分别记账，不把计划渠道都当当前开发阻塞。 |
| [HXA-125](../../development/tasks/HXA-125.md) | 收尾验收 | 已有导入与匿名样本不重做；只补缺失真实认证、拒绝、撤销、重连证据。 |
| [HXA-126](../../development/tasks/HXA-126.md) | 待决策 | 真实OAuth产品尚未接受，不排入当前实施主链；不要求等所有125厂商完成才做方案。 |
| [HXA-129](../../development/tasks/HXA-129.md) | 待决策 | 与207的现有安装/禁用闭环区分；只有新增共享所有权与版本journal才归本任务。 |
| [HXA-130](../../development/tasks/HXA-130.md) | 待决策 | 纯离线格式fixture可准备；不因为依赖129而阻止文档审查，也不提前开发市场后端。 |
| [HXA-190](../../development/tasks/HXA-190.md) | 收尾验收 | 旧APK安装、文件路径、压缩和主题大包拆出；本任务只收口订阅行为与剩余服务证据。 |
| [HXA-191](../../development/tasks/HXA-191.md) | 待实现 | 审批折叠已归201，Goal创建归208，首次配置归205；这里只保留主题与会话/历史检索。 |
| [HXA-192](../../completion-records/HXA-192.md) | 收尾验收 | 门禁修复/主分支集成不再当未做项；PlanSubmitIntegrationDeviceTest不等于用户审阅执行闭环。 |
| [HXA-193](../../development/tasks/HXA-193.md) | 收尾验收 | 单APK生产接线已存在；不重做UID改造，不再以help输出当执行证据。 |
| [HXA-194](../../completion-records/HXA-194.md) | 已交付 | 保留独立详情页；202只做跨页面导航，不重复实现命令结果模型。 |
| [HXA-195](../../development/tasks/HXA-195.md) | 待实现 | 已有最终结果不是流式日志；不等后台Job和PTY组件决定。 |
| [HXA-196](../../development/tasks/HXA-196.md) | 待实现 | Goal后台续轮已归208；这里是独立进程owner/租期/日志/对账，不强制所有启动出卡。 |
| [HXA-197](../../development/tasks/HXA-197.md) | 待实现 | 人工终端范围已接受；剩余是PTY组件许可证和真实目录映射，不重审整份ADR。 |
| [HXA-198](../../development/tasks/HXA-198.md) | 待实现 | 多会话范围已接受；不再写等待手动并发ADR接受，不开放模型PTY输入。 |
| [HXA-199](../../development/tasks/HXA-199.md) | 集成验收 | 保留终端性能、后台真机和恢复集合，不与206重复建设另一套执行器或全产品验收。 |
| [HXA-202](../../completion-records/HXA-202.md) | 已交付 | TasksScreen与dashboard已存在；任务到会话/命令/产物的路径中，任务导航与状态投影已交付，命令详情与产物交付分属194/203，不重建任务列表。 |
| [HXA-203](../../completion-records/HXA-203.md) | 已交付 | 产物交付闭环按四象限矩阵与批次A出口旅程交付（四态可用性、变更横幅、管线导出/取消、无查看器外部打开、返回产生任务）；不扩成通用文档解析或远程Git项目。 |
| [HXA-204](../../completion-records/HXA-204.md) | 待实现 | 恢复展示和显式操作是增量；不重做Goal/审批状态机，不用统一resume覆盖不同副作用状态。 |
| [HXA-205](../../development/tasks/HXA-205.md) | 待实现 | 吸收191首次引导；与194命令详情分开，覆盖真实初始化/修复而非又做Runtime打包。 |
| [HXA-206](../../development/tasks/HXA-206.md) | 集成验收 | 删除工具级ASK/ALLOW/DENY固定用例，使用工具二态+预设/CUSTOM；终端全套由199负责。 |
| [HXA-207](../../development/tasks/HXA-207.md) | 待实现 | 不依赖126/129/130未来OAuth或市场；不复用201旧三态UI；已有导入服务继续复用。 |
| [HXA-209](../../completion-records/HXA-209.md) | 待实现 | 保持主优先级；不等待未来PTY/后台Job才交付现有入口，未来入口在196/197接线时继承契约。 |

## 关键源码与证据

- RootAccessController/RootSessionManager/RootTools及HXA-188证明已有实现与有限真机证据；存活授权与完整App恢复不能据此全部关闭。
- MainActivity使用默认MaterialTheme，SessionListSection按归档过滤；此次未发现完整系统深色主题与会话历史搜索接线，因此191保留这两项，配置准备归205。
- PlanSubmitIntegrationDeviceTest直接走生产Dispatcher与Room，TasksDashboardDeviceTest覆盖审阅对话框与取消；这些不等于审阅→执行→当前授权→恢复全链证明，192保留。
- TasksScreen、ArtifactsScreen、GitWorkspaceReader/GitStatusScreen及对应设备测试已存在，不再写“没有任务列表/产物页/Git只读界面”。完整Git写入/远端凭据仍未据此成立。
- Runtime集成的现有专项与本地全门禁见[接手证据](wip-takeover-2026-09-16.md)；CI配置存在不代表当前远端运行通过，193保留资产/升级/实际CI收尾。
- 新授权任务仍以ADR-PERMISSIONS-001为目标；后台Job、PTY、实时日志没有因为Goal208后台续轮而自动完成。以现有同步结果和状态接口为起点补能力。

本次不接受proposed ADR、不引入新HXA、不改业务代码，也不替用户提交商店或调用付费服务。
