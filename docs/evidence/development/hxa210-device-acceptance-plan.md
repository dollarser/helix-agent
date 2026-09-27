# HXA-210 bounded 设备验收计划

状态：**passed（bounded Workspace 范围）**。所有者授权的 API29/API36 × consumer/developer 已实际执行 192/192，见[模拟器证据](hxa210-emulator-verification-2026-09-27.md)。不启动完整 183-class baseline，不使用真机或真实 Provider/账号。下列为原验收计划；最终覆盖和方法限制以结果证据及[收口审查](hxa210-closeout-audit.md)为准，不能将原计划中的每个故障切点都视作设备实际注入。

## 矩阵与证据

目标为 API29 / API36 × consumer / developer。每次运行先记录获准的设备 serial、API、ABI、flavor、源码清单哈希、应用和 AndroidTest APK SHA-256。使用独立测试会话和命名文件；禁止清空应用数据代替恢复测试。不同源码、APK 或 fixture 的结果分别记录。

## 已准备的自动化入口

| Fixture | 断言 | 当前证据边界 |
| --- | --- | --- |
| `com.helix.app.chat.WorkspaceBindingDeviceTest` | Room 关闭重开、会话切换、历史请求绑定、跨会话产物保留；清理 fence 后重开拒绝迟到产物 | 四组合通过；Room reopen 不等同真实进程重启 |
| `com.helix.app.chat.WorkspaceRequestBindingDeviceTest` | Loopback 请求挂起后切换，旧响应审批仍使用原路径；当前 DENY 阻止实际写入；成功产物引用旧资源 | 四组合通过；无外部网络账号 |
| `com.helix.app.files.ManualSafFileDeviceTest` | 可控 DocumentsProvider 的能力边界、仅 delete 无 write、备份恢复 | 四组合通过；不替代系统 picker/OEM Provider |
| `com.helix.app.ui.WorkspaceLayoutDeviceTest` | 320/360/412dp 中文/英文大字体、默认字号，长目录及不可用状态，选择按钮可达 | 四组合交互通过；清理确认截图和系统 picker 仍需单独检查 |
| `com.helix.app.chat.WorkspaceProcessRecoveryDeviceTest` | fence+rename 后真实 kill；新 PID 读取 durable 状态，显式清理 | 四组合通过；不扩大到全部 crash 切点 |

已授权范围内仅选择上述类及相关 Workspace fixture，不能把 class 编译或测试方法存在记为通过。执行结果以[模拟器证据](hxa210-emulator-verification-2026-09-27.md)为准。失败需保存 Harness durable 状态、调用参数、结果和文件事实，不用模型描述代替执行证据。

## 原计划专项与方法边界

1. **实际进程中断**：在创建目录、清理隔离后、purge 中途、备份删除/恢复阶段分别中断并重开。启动不得自动重放删除；显式恢复不得覆盖同名文件或异身份目录。记录中断点和前后 Room/文件事实。
2. **运行任务与排队调用**：在实际 Job/手动终端占用时请求私有目录清理，应拒绝；切换目录不得修改既有 Job 输入/output 引用。结束资源占用后再显式清理。当前 host 参数测试不作为设备 Job 通过。
3. **系统 SAF**：通过系统 picker 选择真实可用目录；撤销和重授 grant、Provider 失联/只读、部分写入、无 rename、恢复目标冲突。不得将 SAF 当本地 cwd，或静默复制目录以伪装支持。
4. **UI**：320/360/412dp，各测默认字号和大字体。检查会话目录选择、来源不可用提示、清理永久删除确认、长目录名、错误/恢复说明、滚动与按钮可达。consumer 与 developer 的终端能力提示必须与实际后端一致。保存截图、尺寸、fontScale 和操作结果。
5. **旧产物与当前授权**：切换后打开旧产物仍访问旧资源并校验 hash；当前 DENY、工具禁用和 grant 失效均不能被旧审批绕过。

## trajectory 边界

HXA-227 固定 host baseline 保留。设备 trajectory 使用此次稳定 APK 身份，先跑 bounded Workspace 专项，再按所有者单独授权的 baseline 范围执行。fixture/environment 不可比较时只报告覆盖变化，不声称性能收益。未运行项始终记为 not requested；获准但未完成才记 pending。
