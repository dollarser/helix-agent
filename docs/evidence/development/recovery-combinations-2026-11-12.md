# 取消、撤权、恢复与清理组合补验

## 范围与选择

承接 HXA-232、235、236、238、239、248 的既有实现，优先验证实际相邻边界，不重做恢复、插件或 Job 子系统。不把有限组合扩展为全部 API/OEM、低内存/Doze、第三方生产服务或真实模型验收。

- 观察等待：停止一个观察者、撤销另一个观察者的读取权限、物理查询仍阻塞；不得提前腾出查询槽或披露迟到结果。重新创建观察服务不能接纳已失效绑定。
- Runtime：取消后真实进程退出、scratch 清理、原收据、重复 ACK 与下一执行；长期历史清理不得删除未知/仍物理运行任务或已打开的结果 reader。
- 插件：请求已发送且 executor 未返回时取消并卸载；引用/凭据需保留至实际返回，旧捕获 executor 不得再次发送。安装的 preparing、before-commit、after-commit 三个切点分别在保留选择和已撤销选择下真实杀进程，核对恢复内容、原身份、凭据、选择及 Skill 读取门禁。
- 会话权限：两个会话分别配置权限，再撤销第一个会话写权限，真实杀进程后验证不会恢复成 FULL_ACCESS，也不修改另一会话。
- 既有自动恢复、持久结算与 J1/AUTO 原执行身份套件作为邻近回归；Room 重开、服务重建与真实进程死亡分别记录。

## 首跑发现与修正原则

1. `ConnectorSendBoundaryDeviceTest` 的三个测试首跑失败：旧 oracle 仍要求裸 `Cancelled`，生产已按现行 MCP 契约返回 `CancelledWithEffectTruth`。改为同时断言 NOT_SENT、audit.requestSent=false、requiresReview=false，并保留实际连接/发送次数、凭据及物理返回断言。没有回退生产结果类型，也不声称 bootstrap 一定无效果。
2. `JobAwaitLoopDeviceTest` 六项首跑失败：归档导入 fixture 未显式选择 snapshot，现行默认原位执行在没有绑定目录时正确拒绝。为该归档导入旅程显式传入 `workspaceMode=snapshot`，没有改变生产默认，也不把该套件当原位执行验收。
3. 第二项暴露真实错误分类：START 尚未落原绑定时，随后 COLLECT 在只读 binding 解析阶段抛异常，被上层保守标为 NEEDS_REVIEW。这个阶段尚未取得控制许可、查询 Runtime、导入输出或结算预算。修复限定在可信原句柄准入，后续执行/导入异常仍保守处理，未知实际效果不被抹去。

4. 最终收取回归首轮8项中1项失败：旧测试假定FAILED没有归档，与现行失败日志保留契约冲突。修正为核验原FAILED/exit7、实际stdout/stderr字节、归档无用户文件、无artifactDirectory及resultAvailable=false；没有关闭或放宽原业务失败断言。

## 验证

- 最终 `scripts/check-all.sh --debug` 通过：全仓 Debug 单测、spotlessCheck/detekt、双渠道 lint、Debug APK 与 AndroidTest 编译、源码/文档和制品边界。新增六个 JVM 用例覆盖共享观察、清理/重开、窄拒绝和解析反例。初始格式/静态失败已修正并重跑，未跳过检查。
- Developer，Helix_API_36，API36/arm64-v8a：最终同一主候选 **49项设备测试通过，0失败、0跳过**；另 **7次预期进程终止**，后续通过不同 PID 与持久事实验证，不将预期 crash 单算通过测试。
- 明细：PRoot 实际取消/清理与历史容量2项，MCP发送/取消/卸载3项，Room观察日志3项，会话撤权真进程恢复1项，自动恢复8项，结算恢复10项，J1/AUTO6项，插件三切点×选择/撤销6项，原控制2项，原结果收取8项。最后两组包括未 prepared 的失败 START、随机不存在/跨会话句柄、导入失败重试、当前撤权、原工具停用、缺失结果与预算结算失败；原 owner 不提前释放。
- 主 APK、App 测试 APK 和 Runtime 测试 APK 均已安装，实际安装字节 hash 与成功构建产物一致。收取日志oracle校正后，同一主APK补跑收取8项；其他41项的生产代码及测试源码保持不变，旧/新测试APK和源码映射均留存，并在验收结束复核。日常 Developer 主 APK 已覆盖更新；未清空应用数据，未使用真机，未提交、推送或发布。
- 最终原始命令日志、JUnit、输入和安装身份在本地 `build/recovery-combinations-final/`，生产修复与最终测试修正的完整门禁日志分别为 `build/recovery-combinations-verified-gate.log`、`build/recovery-combinations-log-oracle-gate.log`；首跑失败和中间校正仍留在 `build/recovery-combinations*`，没有覆盖成成功。设备运行使用仓库共享 host/device slot。

## 明确边界

真实进程死亡覆盖会话权限及插件安装切点；PRoot新增测试是实际执行/取消加journal重开，不是Runtime进程死亡迁移。共享查询阻塞交叉使用JVM受控端口，不冒充真实Binder阻塞。J1/AUTO使用scripted模型经生产Provider/Dispatcher/Runtime，未调用真实模型；没有证明新的策略收益。插件HTTP为本机协议fixture，未验证第三方生产服务或账号。权限恢复走生产保存服务，不替代输入框UI、IME和全部自定义权限矩阵。完整OEM、热压/Doze/低内存、原位目录/分支/Runtime死亡交叉矩阵继续保持各HXA原开放边界。
