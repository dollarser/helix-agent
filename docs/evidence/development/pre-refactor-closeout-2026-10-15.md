# 重构前有界验收收尾

日期：2026-10-15。范围：[HXA-284](../../development/tasks/HXA-284.md)、[HXA-285](../../development/tasks/HXA-285.md)、[当前状态](../../development/status.md)。Owner允许设备/模型/真实MCP并要求先收尾；本轮选择当前日常API36/Developer与已有localhost:30008 Qwen3.8-27B，只读取合成文件与指定官方MCP对象。未连接真机；按主会话通知，长稳和提前结束排查保持暂停，不作为本轮隐含工作。

## 已执行

- PdfReadingDeviceTest实际4项passed：私有隔离进程声明、真实PDF页文字/渲染/红色区域裁剪、原字节未变、越界/损坏/过期失败，以及平台SAX读取中文DOCX表格和绿色内嵌图像。原始流见build/oauth-ux-2026-10-15/device-v2/documents-native.log。
- ContextHistoryDeviceTest实际6项passed，包括3项新增Room引用/物化限额/checkpoint边界和原历史边界。原始流见同目录context-room.log。没有把10月14日仅编译当作设备通过。
- 新DocumentModelDeviceTest通过真实Provider/ChatService/工具链分别读取合成PDF两页与DOCX段落/表格；2项passed，真实documents.inspect/read均完成，独立核对ORANGE-42、完成订单北京42/上海9、验证PENDING，原文件字节不变。7次业务模型请求，服务usage合计输入68518/输出859，仅此固定样本，不是账单或A/B收益。原始流在build/pre-refactor-models-2026-10-15/run-v2/documents.log。
- 现有真实上下文测试普通正文版本实际通过3次压缩及3次跟进，均保留原代码、禁止删除和PENDING事实。随后新增并单独执行引用仅来自保存快照、原消息不含代码的变体，也通过3次压缩及3次跟进；日志有LIVE_BOUND_REFERENCE=accepted/originalBodyHasCode=false。原始流run-v3/context-summary.log，测试APK SHA256 efc842804d5b85e61eb4b5b24fe6704ecc0b327e4a01d506518eabcd8368960e；不继承普通正文版本通过结论。
- HXA-277相关当前设备切片：原PluginLifecycleDeviceTest6项、NativePluginRepairDeviceTest1项、TurnCancellationRaceDeviceTest6项实际通过。覆盖唯一安装身份、选择/凭据/历史、取消更新不改原选择、native修复不创建权限，以及持久取消与未退出executor/压缩事务边界；并非真实进程死亡或OEM迟到回调全矩阵。原始流lifecycle-v1/plugin.log及lifecycle-v2，后者测试APK SHA25635cd8a72bb19312cd0ecb84b8f9f5d2fd9896733fa2cfe289153925153a41346；系统无障碍设置before/after相同。

## 失败与测试硬化

新文档夹具初轮没有先进行Provider连接测试，生产准入正确拒绝创建会话，2项失败且没有文档业务模型调用。夹具补真实连接测试，并把会话创建纳入finally资源归属，配置失败仍清理自有Provider；不削弱产品准入。原失败日志run-v1保留。失败轮两条自有Provider及旧上下文夹具归档会话已按前后备份差异确认精确身份，通过正常Provider/Privacy服务清理；临时清理类移除并恢复原测试APK。final-preservation核对六张原用户表逐行hash一致，数据库integrity/FK通过、无ADB reverse残留；私有备份未入库。

上下文模型夹具改为退出实际执行后删除自己的合成会话，不再把已完成测试永久归档到日常库。引用变体沿现有Room快照与同一压缩入口，不新增生产模型策略、第二套上下文或更改窗口/预算。

NativePluginRepairDeviceTest初轮选择Mobile Use时触发MOBILE_USE_BACKEND_UNAVAILABLE，尚未执行repair；这是夹具缺少真实后端准备，不是产品应该允许无后端启用。改为既有OwnedAccessibilityBackend连接及finally恢复，原选择/授权/config断言保留；当前复测passed，未跳过失败或修改生产准入。

## 仍需分开的范围

完整主机门禁首次all.log退出0；最终收紧OAuth取消/编辑后的连接意图后，all-final.log再次退出0。最终API36 OAuth4项、原生文档4项和Room6项再次通过，主APK SHA256 81f9157ff6de2ce615a9d8cd2e4218ded069d74a6afa9ccb847f07e1894627e5，测试APK7776b67f97def37708c5b792b4dd15f8c8f40215da157633c0c02c19cf74f072。Qwen文档/引用测试使用各自冻结制品，最终生产差异仅在OAuth界面意图，不把不同整包hash混称同一轮模型证据；修后的Native repair夹具另有独立测试APK身份。

修正Native repair夹具后，两渠道AndroidTest编译及detekt实际退出0。随后all-lifecycle-final.log在执行通道变化后缺少终态、原进程已退出，不当通过或应用失败；已完成设备/账号动作不因断连重跑。仅补跑当前全仓主机门禁，all-reconnected-final.log及status.json实际记录COMPLETED/exitCode=0，覆盖228项主机脚本、完整JVM/spotless/detekt、双渠道Debug/Release lint/APK、41份依赖锁与渠道/UID/Runtime边界。最终final-preservation-v3再次核对六张原用户表hash一致、数据库integrity/FK及无reverse残留。日常恢复结果另按实际原生工具状态结算。

最终原生恢复restore-v4退出0，Notion仍为原OAuth账号且仅notion-search/fetch启用；原Linear工具保持未启用。初始化时固定1秒等待、UIAutomation返回null root曾使恢复脚本在导航前失败，原日志保留；改为有界等待有效新树，每次删除自有旧dump，避免把旧页面用于新动作。没有重试注册/撤销/业务写入，最终UI证据notion-final-restored-v4.xml留在忽略目录，自有sdcard dump已移除。无真机、长稳、提前结束专项或发行操作；并行文档/历史脚本未被覆盖或混入本次提交。

自然OAuth刷新/厂商撤销由HXA-126独立记录。API29视觉PDF、16KiB实体设备、更多OEM/大文档资源峰值与真实Word排版不由当前小夹具证明。R2-B需要先选具体有证据的策略变量并冻结clean对照；本次没有为追求“待做清零”任意改生产策略，也不把单样本usage当作收益。正式发行仍缺稳定身份/签名等Owner输入；未来重构提案不混入本轮实现。
