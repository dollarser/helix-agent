# HXA-096 `root.exec` 产品必要性检查

日期：2026-09-05

## 结论

当前没有已批准的 Helix 产品用例必须依赖任意 Root shell。HXA-095 的参数化只读工具已经覆盖当前需求：真实状态、作用域内文件读取、包信息、进程快照和有界脱敏日志。因此 HXA-096 选择**暂缓实现** `root.exec`，不新增 descriptor、executor、通用命令 Binder 协议或开发者控制台。

这不是永久禁止。只有项目所有者提出一个无法由高层参数化工具满足的明确需求后，才重新打开 HXA-096；届时仍须满足 L3、仅 developer UI、逐次完整命令审批、禁止项检查、独立审计和永不进入普通模型 Tool Registry 的既有约束。

## 机械门禁

- `RootOperationRequest` 只有 `FileRead`、`PackageInfo`、`ProcessList`、`LogRead` 四种结构化请求，没有 command/script/secret 字段。
- `RootTools` 只注册 `root.status`、`root.file.read`、`root.package.info`、`root.process.list`、`root.log.read`。
- `scripts/verify-variant-boundaries.sh` 扫描 developer APK，发现 `root.exec` 字符串即失败。
- `AutomationTools` 和 `android-ui-task` 与 Root 控制台无关，不能引入或调用 Root 命令。

## 重新评估触发条件

重新评估必须同时给出具体任务、不能采用高层工具的原因、允许的命令/路径范围、输出上限、取消与失权语义、RootService crash 恢复方式，以及专用 rooted 真机攻击矩阵。没有这些输入时保持未实现是本检查点的产品决定。
