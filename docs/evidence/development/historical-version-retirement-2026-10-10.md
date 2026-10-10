# 历史版本脚本与日志整理

日期：2026-10-10。承接[HXA-267维护](test-script-maintenance-2026-10-10.md)，Owner进一步允许删除历史版本脚本和原始日志，必要事实汇总到文档。本记录不重跑历史测试，不证明当前APK或设备通过。

## 保留的事实和方法

旧报告进一步整理后，历史事实集中到[客户端交互验证经验](historical-client-ui-validation.md)及[Provider与发布验证经验](historical-provider-and-release-validation.md)，保留阶段性失败、最终验证、后续修订与适用边界。当前v0.0.5正式来源仍见[v0.0.5报告](v0.0.5-release-2026-10-06.md)；当前runner/逐方法验证方式见[维护手册](../../development/debugging-playbook.md)。版本专用冻结校验不是当前门禁，历史结果不作为当前设备通过。

## 本轮删除与溯源

删除62个文件：23个版本专用脚本（11个原跟踪、12个本地未跟踪）和39个旧日志/导航输出/发布产物；共216,059,832字节（约206MiB逻辑大小）。其中v0.0.4本地发布目录的六个文件包括两渠道APK、BUILDINFO、SHA256SUMS、旧发布说明及远端对象描述。版本号、提交和验收边界已转入上述主题摘要；SHA见[逐路径清单](../../../scripts/debug/archive/2026-10-10-historical-version-retirement.json)。SHA用于识别来源，不代表原始字节仍有备份；未跟踪脚本和已删日志不能从Git恢复，按Owner指示保留方法和摘要而非另存一份原始材料。

原跟踪脚本可按清单baseCommit/path从Git查看，不恢复为当前安装或验收入口。当前使用`run-owned-emulator.py`、`run-acceptance-matrix.py`、`instrumentation_junit.py`、`summarize-owned-runs.py`等支持入口，并按维护手册重新授权/冻结/验证。历史报告保留当时结果和失败，不把旧数量当当前门禁。

未删除当前回归、稳定runner、仍被调用的dated helper、FFmpeg配方/vendor/许可输入、PTY实验、未关闭偶发故障与最近有界验收原始材料、工作树恢复包和当前v0.0.5产物。不是对scripts/debug或build目录一键清空，也不是声明所有剩余历史文件必须保留。

## 本轮验证

`scripts/check-all.sh --source`通过：206项host回归、文档/ADR/国际化与秘密扫描。每个删除路径执行前hash复核，执行后62个文件均不存在；保留的tracked及untracked脚本没有调用已删入口。最终文档链接检查通过（808份Markdown、251个HXA）；暂存门禁通过（20个变更blob），git diff --check通过。日志在ignored的build/historical-retirement-20261010/source.log，删除核对在deletion-verification.json。未修改Android代码，本轮不重做Gradle/APK构建。设备和真实模型为not requested；历史21/21和6/6不计入本轮通过。
