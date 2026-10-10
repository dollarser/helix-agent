# 页面迁移与真实模型连续压缩补验

日期：2026-10-10；Owner当前明确允许模拟器和既有模型，并确认本机30008服务可用。范围：[HXA-262](../../completion-records/HXA-262.md)，补验HXA-259功能包迁移和既有ADR-AGENT-002压缩契约；不改变生产提示词、预算或策略。

## 实际环境与结果

稳定owned runner经shared host slot逐个启动同一Helix_Main_Verify_API36的fresh只读API36 arm64实例，4096MiB/2核。Consumer页面5676、Developer页面5678各17/17通过、零失败/skip；选用PinyinComposer、ProjectLifecycleUi、ProjectSessionsUi、ProviderSettingsForm、ProviderContextRecovery、ProviderModelSelectionFeedback、GroupedNavigation原测试。覆盖项目创建改名删除、成员改名归档恢复/加入状态、表单缺项定位/编辑保存、上下文读失败重试、模型管理不偷改会话、短屏大字体导航与实际拼音预编辑/中文commit。通过限于这些精确断言，其他IME/OEM和全产品交叉矩阵未由此验收。

Developer真实模型5680共2/2通过、零失败/skip（原Pinyin及LiveContextCompaction）。用户确认可用后GET /v1/models返回Qwen3.8-27B、max_model_len 262144；通过本实例reverse 30008访问既有服务，仅新建合成Provider/会话，不改其他账号。原旅程执行三次compact及三次跟随回答，检查ORANGE-42、禁止删除原文件、验收PENDING；原历史ID保留。fixture显式输出8192、reasoning OFF，原生产默认与摘要余量不修改；OFF在兼容协议不保证服务端不推理。整个旅程有1200秒上限，单次等待180秒；不重复整轮以筛选成功。摘要ModelCall用量按实际日志保留，逻辑六阶段不等于声明全部HTTP请求或成本数量。

## 模型事实摘要

以下仅为合成fixture标记，未存凭据或用户对话。最终runner只取最后2000条logcat，未保留前两次摘要标记；窗口、前两次用量与首次跟随PASS来自本轮PID57879存活时的owned logcat进度工具输出，另存live-context-observed-progress.txt并明确为工具观察转录，不冒充原始终末日志。第三次用量、两个重复周期及最终状态来自原test-logcat.txt；JUnit原始2/2结果独立保留：

```
LIVE_CONTEXT_WINDOW=262144
LIVE_SUMMARY_USAGE=[{"inputTokens":3273,"outputTokens":998}]
LIVE_CONTEXT_SUMMARY_AND_FOLLOWUP=PASS
LIVE_SUMMARY_USAGE=[{"inputTokens":4052,"outputTokens":2486}]
LIVE_REPEAT_COMPACTION cycle=0 elapsedMs=15787
LIVE_SUMMARY_USAGE=[{"inputTokens":4311,"outputTokens":5482}]
LIVE_REPEAT_COMPACTION cycle=1 elapsedMs=30337
LIVE_FINAL_STATE turns=[(COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null), (COMPLETED, null)] blocked=null
```

一次旅程不能证明普遍摘要质量、成功率或其他服务收益。本次通过不抹除历史4096摘要截断、旧1-token夹具输出配置或缺逐调用证据的Goal OUTPUT_TOKEN_LIMIT；那些来源及未知仍保留。没有通过提高生产阈值或削弱旧断言获取通过。

## 命令与制品

沿用HXA-261导航修正后的全仓主机/双渠道Debug lint/App/AndroidTest编译通过；本检查点不改源码，仅执行原测试和更新文档。稳定runner每次冻结App/Test SHA，检查own PID/AVD并finally关闭；键盘偏好及原拼音真实验证每实例执行。页面命令：run-owned-emulator.py --avd Helix_Main_Verify_API36 --port 5676（Consumer）或5678（Developer） --memory-mb 4096 --raw-results --classes 上述七类 --timeout 900；模型Developer --port 5680 --reverse-port 30008 --instrument-arg contextPort=30008 --instrument-arg contextModel=Qwen3.8-27B --classes PinyinComposerDeviceTest,LiveContextCompactionDeviceTest --timeout 1200。APK/Test均使用HXA-261冻结projection-load-ready-r2；包/runner沿各渠道com.helix.agent[.developer].test/com.helix.app.HelixAndroidJUnitRunner。

原始owner/artifacts/device-properties/closed、instrumentation、test-logcat及JUnit在ignored build/maintenance-followup-20261010/feature-pages-{consumer|developer}/和live-context-qwen/；外层日志feature-pages-host.log、live-context-host.log。完整复现选择原类名com.helix.app.ui.*，不借用日常实例。

以下Developer模型命令为本轮完整入口；重复时须使用新的输出目录及空闲端口，并重新核对制品SHA，不能覆盖原证据。页面回归用同一入口，去掉reverse/context参数，按渠道替换App/Test/runner和端口，将classes改为`com.helix.app.ui.PinyinComposerDeviceTest,com.helix.app.ui.ProjectLifecycleUiDeviceTest,com.helix.app.ui.ProjectSessionsUiDeviceTest,com.helix.app.ui.ProviderSettingsFormDeviceTest,com.helix.app.ui.ProviderContextRecoveryDeviceTest,com.helix.app.ui.ProviderModelSelectionFeedbackDeviceTest,com.helix.app.ui.GroupedNavigationDeviceTest`，timeout为900。

```sh
python3 scripts/with-host-slot.py -- python3 scripts/run-owned-emulator.py \
  --avd Helix_Main_Verify_API36 --port 5680 --memory-mb 4096 --raw-results \
  --apk build/maintenance-followup-20261010/projection-load-ready-r2/developer-app.apk \
  --test-apk build/maintenance-followup-20261010/projection-load-ready-r2/developer-test.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes com.helix.app.ui.PinyinComposerDeviceTest,com.helix.app.ui.LiveContextCompactionDeviceTest \
  --reverse-port 30008 --instrument-arg contextPort=30008 \
  --instrument-arg contextModel=Qwen3.8-27B --timeout 1200 \
  --output build/maintenance-followup-20261010/live-context-qwen
```

- feature-pages-consumer App `b6c8a9972e7278d7c3a4564494ce88fe6a7b4a8a6f7a86ce690efd9eca5ffbe5`；Test `98c2fa072314b6db2d12101d7da9be16d2c02e383ddef09abecef9a83e726d17`。
- feature-pages-developer App `557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633`；Test `0c29e74102d72b5d600d86f1b8e101b5002d540749feae811393175d82493674`。
- live-context-qwen App `557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633`；Test `0c29e74102d72b5d600d86f1b8e101b5002d540749feae811393175d82493674`。

页面/模型三个实例都自然结束并由finally关闭；日常5554保持运行，未覆盖日常APK、清数据、修改系统安全权限或启用持久访问。首次地址超时在用户确认后恢复，未更换账号/模型、重启用户服务或删除数据。真机卡顿黑屏仍缺指定实体设备与日志；完整并发/恢复组合、其他IME/OEM/长稳和需要外部账号/域名的验收独立。

本轮`./scripts/check-all.sh --source`退出0：160项host、795份Markdown/245项HXA、36个ADR、国际化与秘密门禁通过，日志page-model-source-final.log。最终只剩日常emulator-5554登记，原PID64790继续运行；三个自有实例closed.exit均为0。暂存区单独检查，只提交本轮文档，不纳入历史未跟踪脚本。
