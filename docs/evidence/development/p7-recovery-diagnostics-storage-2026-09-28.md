# P7 恢复 UI、诊断与空间边界

## 本轮变更

所有者要求先优化 prompt/skill-003，再继续错误恢复 UI、诊断脱敏和空间管理。prompt/fixture 的 clean `fce488ce` 15/15 见[独立证据](prompt-fixture-v2-2026-09-28.md)，不与本页后续 UI APK 混用身份。

- 会话导出准备失败时，原先一直禁用选择文档按钮，只能关闭重开；现在可在原对话框重新准备。清理仍必须成功，重试不会自动选择目标、导出或调用模型。取消协程仍传播，不把异常显示成成功。
- 现有无正文 `DiagnosticBundlePreview` 原先未接入设置页面，现在“诊断与审计”提供主动预览入口，经 service 在 IO 线程读取。报告加入应用版本与 applicationId，保留已有显式字段白名单与 16 KiB 上限。只含进程状态/时间、Turn/correlation ID、异常类型/栈指纹及系统退出概要；不采集 prompt、工具参数/结果、文件正文、异常 message、Secret 或原始堆栈。
- 预览成功后才能主动复制当前快照；打开预览不复制、不自动上传。失败只显示固定本地化文案，可原地重试；关闭取消预览协程。中文/英文均可用。
- 页面明确说明报告不是会话备份，也不含完整错误轨迹；现有单会话 JSONL 导出仍可能含正文，已知凭据净化不代表匿名，二者不混为一个隐私承诺。

## 验证

API36 arm64 独占模拟器，4 GiB/4 cores、4 KiB page，合成数据：

| 范围 | 结果 |
| --- | --- |
| developer：诊断交互/生产设置入口/诊断白名单/导出 UI/RecoveryJourney/清理确认/Session fork | **38/38 PASS** |
| consumer：新增诊断交互/生产设置入口/诊断白名单/导出 UI | **8/8 PASS** |

交互断言包含：打开前未采集；首次失败可重试；预览未改变剪贴板；显式复制与预览内容一致；2x 字体下按钮可见可操作；真实设置路由可打开报告；异常消息中的 synthetic secret 不进入报告；导出 synthetic storage fault 修复后可原位重新准备且 picker 启动数为 0；正常选择文档后输出含 complete footer。此处修复 fault 是 fixture 操作，不声称 UI 会自动修复任意损坏元数据。

RecoveryJourney 验证网络/认证/权限问题的恢复按钮与实际操作、不自动 replay、UNKNOWN 只允许结果查询、取消不自动继续。该普通批次不等同于新的真实进程死亡验证；专用进程死亡证据见[上一批](p7-recovery-first-batch-2026-09-28.md)。

developer app/test APK：`8079c277af104cd2fb7a433eb800ffff57091df66f18d90596a3d76532b29e0f` / `d063dac078ad28e0a127f0d1c225cacff0dab15cd4c373c18badd53e0b13296b`。原始证据 `build/p7-ui-diagnostics-storage-20260928/`、`build/p7-ui-consumer-20260928/`，含各自 APK hash、设备属性、JUnit 输出与 closed.json；两 runner 正常退出、设备已关闭。consumer 首次启动前端口占用使 guard 拒绝，未启动测试；更换空闲端口后正常执行，原日志 `build/p7-ui-consumer-launch.log` 保留。

主机：双渠道 app 单测、lint、debug APK、AndroidTest APK、spotless/detekt；core:storage JVM 与 app 凭据净化/Memory/Workspace cleanup gate；source、国际化和 secret scan 通过。日志 `build/p7-ui-final-host.log`、`build/p7-boundary-host.log`、`build/p7-ui-source.log`。制品编译与主机检查不代替上述设备结果。

## 空间管理核对

| 对象 | 当前保护与本轮证据 | 未闭合边界 |
| --- | --- | --- |
| 会话与共享 Workspace | SessionFork 设备测试验证删来源会话保留共享文件，新 fork 可独立换目录 | 会话/Workspace 统一占用展示未交付 |
| Workspace 清理 | 固定确认目标，busy owner/job 阻止清理，失败释放准入；中英文默认/2x 字体确认与失败展示通过 | 不把阻止删除正在使用的数据改成自动强删 |
| ContentStore 与临时文件 | JVM GC tests：引用保护、保护期、旧孤儿和过期临时文件范围 | 没有全产品统一“可清理字节”统计 |
| 本地模型 | 源码确认 ownership lock + active generation 检查，必要时先 unload 再删；下载主机测试覆盖空间预检、hash/续传/cancel | 本轮未再运行真实模型；取消后的续传片段仍保留，专门的主动清理入口待补 |
| Memory | 独立 Markdown store，hash 前置条件；MemoryService/Tools 主机回归通过 | 本轮没有新增 Memory 占用/设备管理验证 |

本轮不引入统一一键删除，不借可恢复原则移除审批、UNKNOWN、共享目录或正在执行任务的保护。下一步优先完善占用与可清理范围的只读展示、残留下载的明确清理选择，再补长稳/可用性缺口。历史输出截断和偶发多余调用继续开放；真机/OEM、真实账号和发行条件仍独立，不宣称 P7 全部完成。
