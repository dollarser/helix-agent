# 门禁与调试脚本维护

日期：2026-10-10；范围[HXA-267](../../completion-records/HXA-267.md)。Owner要求删除经常变更的计数门禁、失效历史测试和临时脚本，并合并有效部分。基础提交978249b4，初始tracked干净，日期目录中未提交内容单独核对。

本轮参考[OpenCode贡献指南](https://github.com/anomalyco/opencode/blob/dev/CONTRIBUTING.md)的职责分组、复现与验证说明，选择保留现有技术栈和纯host测试自动发现；不采用参考源码。Android生命周期/授权/Room事实、CI host-only和当前设备授权要求继续适用。

## 删除与迁移

实际删除390个脚本/入口/本地草稿：219 tracked、171未提交或ignored（含本次清理完成后自身18个辅助/草稿）。逐路径用途、SHA256、字节数、Git来源及替代见当时的 `scripts/debug/archive/2026-10-10-retired-scripts.json` 清单（历史路径；后续本地归档布局见[脚本整理记录](test-script-maintenance-2026-10-10.md#接续2026-10-15-调试脚本整理)，不作为当前可点击仓库文件）。仅选择确认已完成的源码/文档编辑器、硬编码历史结果汇总、冗余兼容入口及HXA-261～266完成后的本地临时辅助；有实际调用者的领域验证先保留或更新调用。

所有删除前内容保存到ignored `build/maintenance-cleanup-20261010/removed-scripts.tar.gz` 并逐文件校验；未提交文件不能仅从Git恢复。tracked原实现可从清单baseCommit读取，少数本轮调整后删去的编辑器另有校验后的本地副本。不删除build中的真实日志、APK、数据库或失败轨迹，不操作设备或账号。

六类原host覆盖迁入scripts/tests：门禁自测、模拟器防误借/假绿、WebView当前选中包解析、模型评测runner失败与超时清理、锁定FFmpeg闭包/原子提取、历史矩阵非零退出传播。四组原手动host故障/账本自测以隔离子进程进入自动发现，避免其signal handler污染主测试进程，原实现不复制。原断言保留；FFmpeg套件总文件数/固定字节快照改为与实际锁定闭包和manifest比对，SHA/大小上限及拒绝损坏/符号链接保持。独有FFmpeg完整编解码实验与termLib/PTY根因诊断保留，不因没有普通调用方删测试。

纯WebView parser迁到scripts/soak_evidence.py。多个重复owned结果汇总归并scripts/summarize-owned-runs.py；仍需的主题/进程死亡领域断言继续保留并调用共享解析。四个旧runner shim移除，当前调用方改稳定路径。历史报告不篡改为本轮通过，旧路径按Git恢复。

## 门禁变化

十个稳定shell验收入口不再匹配固定OK(N tests)，改复用instrumentation_junit逐方法校验；零结果、失败、跳过、重复或未终止仍非零退出。资源循环逐类验证非空/零失败/零skip，host读实际HelixSoakCases完成事件，不保留CASES_PER_CYCLE=8或其自证断言。资源阈值与同PID/时长契约不变。Room搜索/固定轨迹汇总也改为实际方法及已声明身份。固定副作用次数、身份唯一性、明确评测集合与安全/恢复门禁保留。

可复用方法与命令模板统一到[调试维护手册](../../development/debugging-playbook.md)：投影/会话身份、精确Skill授权、输出限额、连续日志、真实死亡切点、资源采样null及owner检查。文档代替过期编辑器，领域fixture仍验证业务结果。

## 当前验证

`python3 scripts/run-host-tests.py`最终206项通过，包含迁移覆盖与新增的动态结果/CLI/owner/hash回归；四组历史自测均在隔离host子进程执行。含spikes的全部test/detekt/spotless/双渠道Debug lint/App与AndroidTest编译通过，46秒1033 tasks/33 executed。当前修改的tracked Python与shell语法检查通过；source门禁最终source-final.log通过，文档、ADR、i18n与Secret全部通过。两处相对导入补改为稳定matrix后，两个当前入口的--help通过（未接触设备/网络）。暂存内容门禁通过；未提交但保留的旧脚本只有本地调用路径修正，未将其整份加入提交。

首轮文档门禁发现旧审查报告链接到已删除生成器，改指向其Git来源/删除清单，未恢复失效入口或跳过检查。host输出中的注入FAIL/FAIL_FUNCTIONAL是既有回归的合成输入，不是设备结果。只做本轮主机验证，device与真实模型not requested；历史HXA-263～266通过仅作来源，不算本轮重新执行。原始输出在ignored build/maintenance-cleanup-20261010。

## 前序：2026-09-22 稳定入口与 Spike 构建

基于 `8bf2a829`，四个入口迁到 `run-owned-emulator.py`、`run-owned-acceptance-emulator.py`、`with-host-slot.py`、`run-acceptance-matrix.py`；当时暂留的四个 shim 后由本记录删除，不恢复兼容层。普通 Gradle 默认不加载三个 Spike，显式 `-PincludeSpikes=true` 保留实验测试与锁覆盖；`:testing` 四项数据校验不移除。默认/显式项目树、格式、三个 Spike 加 testing 的四任务强制重跑、runner help 与 source/diff 通过；旧测试漏 `reboot_after_setup` 参数仅修输入，未放宽已有设备拒绝。无设备运行或性能改善测量。历史日志为 ignored `build/repository-hygiene/`。

## 接续：2026-10-15 调试脚本整理

Owner 后续明确授权归组、去重和退役脚本。设备基线三工具归 `scripts/device_baseline/`，分类拆为 `expectations.py`；现行恢复/采集夹具归 `scripts/fixtures/`，Mobile Use 评测归 `scripts/run-mobile-use-eval.py`。Composer 恢复统一精确标题查找和有界翻页，保留观察失败停止及原设备断言；路径定位改为从自身寻找项目标记。17 个 FFmpeg 实验只归组，源码/许可/固定 builder 与 vendor ZIP 不变，包内旧日期成员名仍为冻结格式。

退役 30 个一次性改写/诊断文件、3 个重复 host 包装器及2个旧生成清单；有独立断言或消费者的脚本保留。131 个本地历史脚本迁入 ignored `scripts/local/debug/YYYY-MM-DD/`，共享忽略规则代替逐文件 `.git/info/exclude`；本地文档归 `docs/local/`。Git 来源及替代映射见 `scripts/debug/archive/2026-10-15-script-curation.json`，详细恢复包/校验库存留在 ignored `scripts/local/debug/archive/2026-10-15/`。

设备基线原25加3项主机回归、查找3项、完整host234项、FFmpeg合同17项与vendor核验4项、40份Python语法及source门禁通过；没有设备、账号、模型、FFmpeg重建或下载。首次混合批量移动/重写命令被自动审批拒绝而未执行，随后按明确路径先验证替代再移除；后续本地归档调整仅核对对应/忽略/入口，未重跑测试。

同日 Helix Docs 交付曾保留37份tracked文档与4份未跟踪文档的并行改动；之后 `d03cb29b`、`47d74c27`、`9088ce08` 分别承接本地归档、架构历史与研究/预览文档。该一次性工作树清单不再维护，不能据其旧“待提交”状态重新选任务。131脚本的恢复来源仍为上段归档。
