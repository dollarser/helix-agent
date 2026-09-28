# P7 模型发布残留与有界压力回归

日期：2026-09-28；基于 `242c4c40` 的增量。

## 修复

显式清理现在覆盖私有模型目录中因进程中断留下的 SHA-256 命名普通 `.part` 发布副本，以及原有可续传下载。占用和确认文案同步说明两类残留；不删除 `.gguf`、未知文件、目录或软链接。

发布副本的确认 token 绑定 store 实例、发布 revision 与文件 metadata；变化或实例重开后旧确认不能清理新文件。清理与 `publish` 共享 store 同步边界，发布回调中的重入清理也被拒绝；应用入口继续持有原下载互斥锁。清理失败刷新真实文件状态，不宣称跨文件系统事务。

## 验证

- provider/api unit、双渠道 app unit/lint/debug APK/AndroidTest APK、detekt/spotlessCheck 通过：`build/p7-publish-gates.log`。资产 store 7/7，下载器 9/9。
- 新增 AndroidTest 长行导致格式检查失败，提前启动的 runner 被主动停止、模拟器正常关闭，未计通过。修正后的双渠道 AndroidTest APK 和格式检查通过：`build/p7-publish-tests-build-r2.log`。
- API36 arm64-v8a、4 GiB / 4 cores、400 dpi：developer 4/4（14.576 s），consumer 4/4（12.987 s）。证据分别为 `build/p7-publish-developer-r2-20260928/`、`build/p7-publish-consumer-20260928/`；本轮所有 owned emulator 均已关闭。
- 每渠道包含 32 轮 reopen→两类残片清理→过期确认拒绝→注入可用空间为零→已安装模型字节保持一致。并复用清理确认、关闭不删除、模型页交互三项 UI 测试。

这是合成残留与有界资源失败注入：不是实际填满磁盘、真实进程硬杀发生在 publish 中、物理硬件压力或长时间 soak。没有调用真实模型下载或账号。历史截断、偶发额外调用和系统 JNI/Binder 长稳根因仍未关闭。

## 后续顺序

本地可实现的发布残留清理已交付；下一步按所有者请求尝试 BFCL 诊断与 AndroidWorld 小规模试跑。真机、外部账号、发行条件不混入公开评测通过口径。
