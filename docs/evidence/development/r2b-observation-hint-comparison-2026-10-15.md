# R2-B观察提示有界成本对照

日期：2026-10-15。范围：[HXA-282](../../development/tasks/HXA-282.md)。Owner已授权设备/真实模型，主会话随后明确执行A6/B6、最长20分钟；不扩成长稳、提前结束归因或生产策略修改。完整宿主门禁通过后提交准备代码2f31456a，从独立干净检出构建同一主/测试APK。日常账号未参与此矩阵。

## 冻结与方法

源码commit为2f31456a2d03f8a04b04c6087ec3bae98f1f905d，dirty=false，sourceManifestSha为e30253424e2f64da7f1a7ad67d4081961f6a6ad4cd6d8b2c9c84f77347566c91。既有source_manifest/provenance和HXA-227 envelope/compare被复用，没有新增评分oracle。主/测试APK、运行脚本hash及原始结果在忽略的build/pre-refactor-boundaries-2026-10-15/frozen-manifest.json及owned-r2b。

自有只读Helix_Validation_API36_20261012/API36 arm64/Developer、同一VM/1080×2400/density420/font1.0，既有Qwen3.8-27B及同一API报告版本。先实际拼音组合/中文提交1项passed，再固定AB、AB、AB顺序，复用mobileSyntheticForm每次2业务。两臂postObservation=true，实际页面/ATTACHED及后台返回谓词完全相同；仅pluginSkillObservationHint由false变true，B追加已有的附带观察使用指引。没有补跑、换fixture、放宽token保护或更改模型预算。

总窗口从启动owned runner起255.06秒，6个方法及12个独立业务全部passed，包含24次实际HOME/返回检查。均无PROGRESSIVE_FAILURE记录。两臂每次实际hint值进入日志；通过既有compare的promptSha单变量检查。这里toolSurfaceHash及providerCapabilitySnapshotHash是源码/固定配置和公开服务身份的投影，未抓取每个真实请求的wire工具集合；不能将配置受控解释为逐字节请求相同。API报告版本也不能证明不可变SGLang源码或模型上游revision。

## 结果

| 指标 | A：无额外提示 | B：额外观察提示 |
| --- | --- | --- |
| 独立业务 | 6/6 | 6/6 |
| 模型请求 | 37 | 36 |
| 工具调用 | 33 | 31 |
| 服务usage input | 639738 | 624883 |
| 服务usage output | 5318 | 5528 |
| 服务usage total | 645056 | 630411 |
| 任务耗时总计 | 66.532秒 | 65.381秒 |
| 任务耗时中位数 | 10.692秒 | 11.0095秒 |
| 最大单任务耗时 | 12.927秒 | 12.519秒 |

B输入约少2.3%、总耗时约少1.7%，但输出增加、中位耗时变慢；只有6个样本/臂，固定AB顺序、缓存/采样/服务负载未随机控制。usage为服务计量而非账单，任务耗时不含另行HOME/返回核验；本轮没有资源峰值、长稳或普遍效果结论。**没有足够证据推广额外提示；保持生产策略不变，结束这一有限假设，不以继续试跑挑选绿色。**不能把其他尚无具体瓶颈的R2-B策略研究永远作为重构前阻塞，也不把本轮称为完整P5。

## 失败、恢复与剩余边界

第一次干净构建被缺少忽略的Runtime输入正确阻挡；从当前已锁定输入复制5项忽略资产并记录hash，仍通过原资产验证，不改锁、源码或下载可变内容。clean-build-v2实际成功，源码仍clean。主机准备完整check-all及两渠道测试APK/detekt通过，代码提交与矩阵结果分别记录；没有用旧APK补clean标签。

VM退出0，初始拼音与矩阵logcat采集已退出，原临时无障碍设置前后一致，仅原日常模拟器保留；未安装日常主APK或修改真实账号权限。矩阵清理以owned runner实际关闭为证，额外logcat采集等待退出但没有单独记录退出码，不虚写0。

随后按授权在日常实例使用安全到期观测及原指定对象只读/审批拒绝测试，两家各1方法passed；已观察客户端到期UTC，实际均naturalRefresh=not_due，具体时间仅留本机oauth-safe-times.log，不输出凭据。自然刷新尚未触发；没有改设备时间、伪造过期、撤销或重新授权，OAuth授权拒绝回调仍无新证据。厂商撤销/重连需Owner参与，发行身份/签名输入及未连接真机仍独立；长稳和提前结束专项继续暂停。
