# 历史版本脚本与日志整理

日期：2026-10-10。承接[HXA-267维护](test-script-maintenance-2026-10-10.md)，Owner进一步允许删除历史版本脚本和原始日志，必要事实汇总到文档。本记录不重跑历史测试，不证明当前APK或设备通过。

## 保留的事实和方法

| 历史范围 | 必须保留的结论与经验 | 来源 |
| --- | --- | --- |
| v0.0.4发布 | 精确提交05e910039e03095d98649d96a9e64a3a71bcb078；versionCode=4、双渠道debug签名。API36 Developer定向21/21、34.218秒；不代表全P5/真机/商店验收，发布轮真实账号调用为0。初轮测试寻找隐藏消息操作、选择user消息分叉的oracle过期；最终按实际触控和assistant安全边界验证，保留损坏历史拒绝。129条签名回放依赖保留/reopen回归，不能靠跨会话LRU删除仍被引用的记录。 | [当时报告](v0.0.4-release-2026-10-01.md) |
| 工具折叠与会话用量UI | 工具标题优先intent，缺省使用ToolPurpose；详情保留工具名/参数/结果，审批和执行状态可见。界面改变应更新寻找控件的方式，同时保留复制、分叉、权限与损坏历史的行为断言。 | [工具详情](tool-details-collapse-2026-10-01.md)、v0.0.4报告 |
| 订阅模型设置 | 五类订阅配置的本地合成夹具验证不是五个真实账号验证；6/6定向设备结果属于2026-09-30历史执行。初轮行长格式失败修正后重跑。账号绑定/Provider身份和无绑定空态仍需行为断言。 | [订阅设置](subscription-model-settings-2026-09-30.md) |
| v0.0.5安装/发布准备 | 只绑定当时冻结提交与APK的脚本不是支持中的更新入口；开发期数据库重建、通知启停抖动、真实模型安装卸载的历史结论和边界由正式报告保留。当前v0.0.5制品未删除；不运行旧脚本更新日常模拟器。 | [v0.0.5报告](v0.0.5-release-2026-10-06.md) |
| HXA冻结校验 | HXA-183/184/201/210/240/241/242专用校验或提交准备只说明当时固定文件集合；其硬编码HEAD、源码/APK和结果数量不应成为当前门禁。当前方法逐项成功、空/失败/跳过拒绝、owner关闭和输入hash校验继续由稳定解析/runner及有效回归负责。 | 各原HXA及[维护手册](../../development/debugging-playbook.md) |

## 本轮删除与溯源

删除62个文件：23个版本专用脚本（11个原跟踪、12个本地未跟踪）和39个旧日志/导航输出/发布产物；共216,059,832字节（约206MiB逻辑大小）。其中v0.0.4本地发布目录的六个文件包括两渠道APK、BUILDINFO、SHA256SUMS、旧发布说明及远端对象描述。版本号、提交和验收边界已转入上表；SHA见[逐路径清单](../../../scripts/debug/archive/2026-10-10-historical-version-retirement.json)。SHA用于识别来源，不代表原始字节仍有备份；未跟踪脚本和已删日志不能从Git恢复，按Owner指示保留方法和摘要而非另存一份原始材料。

原跟踪脚本可按清单baseCommit/path从Git查看，不恢复为当前安装或验收入口。当前使用`run-owned-emulator.py`、`run-acceptance-matrix.py`、`instrumentation_junit.py`、`summarize-owned-runs.py`等支持入口，并按维护手册重新授权/冻结/验证。历史报告保留当时结果和失败，不把旧数量当当前门禁。

未删除当前回归、稳定runner、仍被调用的dated helper、FFmpeg配方/vendor/许可输入、PTY实验、未关闭偶发故障与最近有界验收原始材料、工作树恢复包和当前v0.0.5产物。不是对scripts/debug或build目录一键清空，也不是声明所有剩余历史文件必须保留。

## 本轮验证

`scripts/check-all.sh --source`通过：206项host回归、文档/ADR/国际化与秘密扫描。每个删除路径执行前hash复核，执行后62个文件均不存在；保留的tracked及untracked脚本没有调用已删入口。最终文档链接检查通过（808份Markdown、251个HXA）；暂存门禁通过（20个变更blob），git diff --check通过。日志在ignored的build/historical-retirement-20261010/source.log，删除核对在deletion-verification.json。未修改Android代码，本轮不重做Gradle/APK构建。设备和真实模型为not requested；历史21/21和6/6不计入本轮通过。
