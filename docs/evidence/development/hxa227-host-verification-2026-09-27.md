# HXA-227 主机验证与 baseline

日期：2026-09-27。基准 HEAD `7a2f47290f6f995f64847be6a203a0f83c6d5a3b`，dirty=true（本次实现）。设备、真实 Provider：not requested。

## 命令与结果

```bash
python3 -m unittest discover -s scripts/tests -p test_agent_eval.py
python3 scripts/run-agent-eval-host.py --output build/hxa227/host-baseline-final
./gradlew :core:model:test :core:storage:testDebugUnitTest :core:agent:test \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug lintDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  spotlessCheck detekt
python3 scripts/run-agent-eval.py compare \
  --baseline build/hxa227/host-baseline-final/envelopes.json \
  --baseline-root build/hxa227/host-baseline-final \
  --candidate build/hxa227/host-baseline-final/envelopes.json \
  --candidate-root build/hxa227/host-baseline-final \
  --output build/hxa227/self-comparison-final.json
./scripts/check-all.sh --source
git diff --check
```

以上最终命令 exit 0。Python 18 tests；fresh host baseline 8 groups PASS（9 个选定 test methods；所在测试类全量执行）。同源自比较只验证比较器接受一致控制条件，不证明 candidate 提升。

完整 host gate 日志：`build/hxa227/host-gate-final.log`，BUILD SUCCESSFUL。model 148、storage 180、agent 204；consumer 812（4 skip）、developer 857（4 skip），均 0 failure/error。条件 skip 仍为已有外部 Connector profile/本地归档输入条件，不计为 PASS。

原始 host XML、源码 manifest、context、run、envelopes、summary 位于 `build/hxa227/host-baseline-final/`；汇总与 APK hash 位于 `build/hxa227/verification-summary.json`。这些是本地 ignored 输出；公开记录只保留范围和身份，不提交机器路径或原始运行环境 properties。

## 身份

- sourceManifestSha：`2a9331f7a4c2c7c59e1e2f89dde025fe4167215321ad2d558bff5969c7c76ad5`
- verifierSha：`5d3641952edfa80c17440b0215442276fba073e67b58bbbed7d6555b340c9bfb`
- host fixtureSha：`79a54bbaaf4905fd6455e82eb083ca7d0ed684888985f95332b3cba471589920`
- environmentSha：`2e059e25a783ce4fe952ecd2a38dc24e040930d3b81b9a2c2941ca133cb413a7`

| 制品 | SHA-256 |
| --- | --- |
| consumer debug APK | `737617de4a87a76d723cfa6327792928a17e31213da4d428de11aeee3c561ed6` |
| developer debug APK | `d34be706b632b468fe4ecb93cfd1399816e503c8128e0dc1c102be1934fb0470` |
| consumer AndroidTest APK | `034be468c8ffe9c9f4a60c1e9beb0c936166fc1d30b1cd5e5c2ef6530443251b` |
| developer AndroidTest APK | `840fd5cf6998c1695e1da12f852356ac464706679b40b1f16c76e725d280f4e2` |

## 边界

AndroidTest APK 仅完成编译。设备八类 trajectory、OS 进程强杀、183-class baseline、真实 Provider A/B 不在本轮已执行范围。host 计数/摘要/状态机断言与端到端设备轨迹分组隔离；未知指标不推导为 0。

生产 source 无改动；diff ownership 限定在测试、eval 脚本、source gate 接入和文档。Workspace 同轮只做官方竞品调研与 ADR/HXA 设计完善，ADR-WORKSPACE-004 仍 proposed。
