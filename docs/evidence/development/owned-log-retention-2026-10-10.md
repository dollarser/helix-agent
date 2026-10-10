# 自有设备日志持续采集收尾

日期：2026-10-10；Owner当前明确授权模拟器与既有Qwen。范围：[HXA-263](../../completion-records/HXA-263.md)。

原runner仅在instrumentation完成后读取最后2000条；HXA-262已观察到较早摘要指标消失。现有runner改为测试前启动同一owned serial的标签过滤logcat，直接持续写原字节；不清设备日志、不扩大系统缓冲、不修改安全权限。原文件名/证据解析保持，新增capture PID/退出码及stderr诊断；结束、超时、取消均只关闭自有采集句柄，提前死亡不假装成功。0.3秒结束排空为有限等待，不承诺ADB断连或磁盘故障时日志零丢失。

方法参考[Android logcat循环缓冲与过滤](https://developer.android.com/tools/logcat)，采用现有工具及生命周期，无产品框架/竞品代码采用。

Host：新增5项超过2500行首尾与非UTF8、死亡owner、提前退出、超时/取消及不退出采集进程kill测试通过；原dated runner两项通过。./scripts/check-all.sh --source退出0，165项host和文档/ADR/国际化/秘密门禁通过，日志log-retention-source.log；不改Android源码，沿用同SHA APK/Test。

当前实际API36 arm64 Developer、Helix_Main_Verify_API36、4096MiB/2核、自有5666；原Pinyin与LiveContextCompactionDeviceTest 2/2、零skip。GET既有30008 /v1/models确认Qwen3.8-27B/window262144，三次compact及三次跟随全部通过。新原始test-logcat.txt包含窗口、三组摘要用量、首次跟随PASS、两个重复周期及最终状态；不需要观察转录补首段。仅合成会话、原fixture8192输出，生产预算/提示词不变。参考HXA-262完整命令，仅端口5666、fresh输出log-retention-model；原owner/artifacts/closed/instrumentation/logcat-capture/stderr及JUnit在ignored build/maintenance-followup-20261010/log-retention-model/。采集SIGTERM退出-15，owned emulator退出0。

合成实际指标：

```
LIVE_CONTEXT_WINDOW=262144
LIVE_SUMMARY_USAGE=[{"inputTokens":3274,"outputTokens":1841}]
LIVE_SUMMARY_USAGE=[{"inputTokens":4302,"outputTokens":2322}]
LIVE_REPEAT_COMPACTION cycle=0 elapsedMs=13972
LIVE_SUMMARY_USAGE=[{"inputTokens":4592,"outputTokens":3548}]
LIVE_REPEAT_COMPACTION cycle=1 elapsedMs=20605
```

App `557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633`；Test `0c29e74102d72b5d600d86f1b8e101b5002d540749feae811393175d82493674`，沿用HXA-261冻结r2。日常5554未操作；真机/全部服务/长稳不由此验收。后续并发恢复与有界长稳分别承接。
