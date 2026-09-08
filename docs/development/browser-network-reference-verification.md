# HXA-155：浏览器网络与 Activity 引用验证

2026-09-08，有界测试完成；不替代真机、其他 WebView 版本或 24h 长稳。

生产 BrowserController / WebViewTabHost 保持不变，测试 APK 允许明文 HTTP 并使用宿主 loopback 慢响应服务，经模拟器 10.0.2.2 访问。服务器收到带唯一 iteration 的真实请求后，脚本广播给测试 Activity 执行关闭、停止后关闭、移到后台并恢复后关闭、Activity recreate。服务器延迟 3 秒响应；广播只用于测试 APK，生产无此接收器。每组均有 100 个不同 iteration 的服务端记录，agent 先于循环附加。

| 路径 | API29/WebView91 创建/删除/目标残留 | API36/WebView133 创建/删除/目标残留 |
| --- | --- | --- |
| 请求到达后关闭 | 1000 / 1000 / 0 | 1000 / 1000 / 0 |
| 请求到达后停止并关闭 | 1000 / 1000 / 0 | 1000 / 1000 / 0 |
| 实际 task 后台、恢复后关闭 | 1000 / 1000 / 0 | 1000 / 1000 / 0 |
| 请求到达后 Activity 重建 | 1000 / 1000 / 0 | 1000 / 1000 / 0 |

共 800 轮，目标类为 LG8 / WV.T6（AwContentsIoThreadClient 子类），全部 unmatchedDeletes=0。全部观测表仍有 6～11 个其他引用，不称进程零引用。Activity 重建在同一进程和同一个 agent 观察窗完成，累计计数经 savedInstanceState 保留，不拼接不同进程结果。后台每轮通过 moveTaskToBack 与宿主显式 reorder-to-front 触发生命周期，不是仅直接调用 pause/resume。

初版固定等 100ms 再关闭无法保证请求已经抵达服务器，校验拒绝该批结果；已改为请求抵达后再触发操作。API29 曾出现 CreatePlatformSocket Operation not permitted，重启专用模拟器后同一测试通过；该环境异常根因未进一步确定，不作为生产修复或引用失败。无效日志保留为 `*-invalid-fixed-delay`、`*-invalid-socket-permission`。

复跑：先按 HXA-154 构建测试 APK；`python3 scripts/diagnostics/run-jni-reference-trace.py <dedicated-serial> --count 100 --scenario <scenario>`，scenario 为 network-close、network-stop、network-background、network-recreate。原始结果在忽略目录 `build/reference-trace/emulator-*-network-*-100/`，包含配对日志、server-requests.json 与 metadata.json。完整 Kotlin/JVM/构建/spotless/detekt/browser lint 命令见 roadmap；均通过，JVM 116 项。

本地与网络有界路径都未复现裸创建的目标弱引用残留。继续保持按需创建及现有 detach/destroy，不添加强制 GC、额外导航或延迟销毁。平台原生问题的 [上游复现材料](native-reference-upstream-report.md) 已整理；发布、上游修复验证、真机和长稳仍未执行。
