# R1 前置问题收口与当前基线

日期：2026-09-28；起点 `49d3bd40`，当前工作树增量。归 [HXA-231](../../development/tasks/HXA-231.md)。未提交、未推送，不作为正式干净源码 P5 或 R1 完成记录。

## 发现与修复

ModelAssetStore.publish 在 streaming 循环开始检查取消，但最后一次 read 返回 EOF 后直到原子 move 没有再次检查。确定性测试在 EOF 同时置取消，旧实现仍发布资产：9 个资产测试中 1 失败，错误为 expected CancellationException but nothing was thrown，见 `build/closeout-asset-red.log`。

在 digest/GGUF 校验后、原子 move 前补最后一次取消检查。不删除已经发布的资产、不承诺取消与文件 move 的跨线程原子事务。另覆盖替换期间来源读取失败时旧模型字节不变、残片清理完成。

## 主机证据

- provider/api、tools/framework、core/agent 与双渠道 app unit：通过，`build/closeout-host-baseline.log`。
- 全部 JVM tests、双渠道 lint/debug APK/AndroidTest APK、spotlessCheck、detekt：通过，`build/closeout-full-host.log`，992 tasks。
- 新增独立 JVM 子进程在真实发布流中 halt，绕过 finally；父进程验证旧模型保持完整、未发布新资产、残片可清理。1/1，通过，`build/closeout-crash-test.log`；不是 Android/OEM 证据。
- 新 Android setup/kill/recover fixture 编译双渠道成功，`build/closeout-device-build.log`。
- 所有新增测试格式化后，全量主机门禁再次通过，`build/closeout-final-host.log`，989 tasks；ModelAssetStore 9/9，host crash 1/1。首次源码/文档检查因新任务缺少规定章节名和 roadmap 表格项失败，已补齐；重验日志 `build/closeout-source-r2.log`。差异空白检查通过。

## API36 定向证据

所有者本轮明确授权；独占只读 AVD `Helix_HXA229_Closeout_API36`，arm64-v8a / API36 / 4 GiB / 4 cores / 420 dpi，不接触其他设备数据。制品 hash、实例 PID、系统指纹和原始 instrumentation 保存于每个输出目录。

- developer：`build/pre-r1-api36-developer-r4/`，13/13，通过。包含实际发布过程中 Process.killProcess，重新 instrumentation 验证不同 PID、旧模型完整、残片可清理及重新发布成功；32 轮低空间/清理合成循环；会话权限 8 项；结果/预算持久结算 3 项。实例退出 0，已关闭。
- consumer：`build/pre-r1-api36-consumer-r2/`，4/4，通过。包含同一真实发布进程骤停恢复、32 轮低空间/清理循环、UNKNOWN 仅查询且重复查询无副作用、取消后不自动继续。实例退出 0，已关闭。

启动失败保留：developer 首次 runner 包名错误被主动终止；第二次端口占用在启动前拒绝；第三次 AndroidJUnitRunner 与项目实际自定义 runner 不符，未运行测试；第四次按实际包名/runner 执行。consumer 首次包名推断错误主动终止，改为构建 metadata 中的包名后执行。上述属于执行准备错误，不作为产品失败或通过，不删除原始日志。

## 边界

低空间测试是注入可用空间为零，不是填满真机磁盘。Android 中断点位于已写部分数据、尚未完成发布，不是断电持久性证明，也未覆盖 rename 后所有崩溃窗口。历史输出截断、偶发多余调用、SAF、OEM/JNI/Binder 根因保持开放。未调用真实模型服务或账号。

R1 尚未实现；其迁移、并发绑定、请求快照及执行准入验收仍归 HXA-231，不因本基线通过而关闭。
