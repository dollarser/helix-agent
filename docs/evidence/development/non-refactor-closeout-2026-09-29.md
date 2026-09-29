# 非重构本地收口

日期：2026-09-29；源码起点为本地 main `70456eb5`。所有者要求收尾所有可收尾部分，结合上一轮讨论，本次处理重构以外的现有工作，不启动新能力、R2/J1 或发行提交。

## 完成范围

- 将 status 中 Memory 设备验收、本地模型安装、SAF 修复、本地合并状态和 P5 锚点更新到已有证据；历史完成/失败记录不改写。
- 移除旧剩余计划的第二份 P0–P7 进度表及过时“未实现”描述。保留执行顺序、退出条件、已知问题处理规则、账号/真机/发行输入以及不启动的候选范围。
- 准备内测任务、独立结果核验和反馈表；不新建遥测，不采集真实账号/个人文件，不将准备完成记为内测执行通过。
- 完整主机发行门禁首次发现两个新增模块缺依赖锁文件：extensions/mobile-use 和 extensions/plugin。按现有 Gradle 固定版本分别生成锁文件，未升级依赖、未改插件功能、未放宽检查。

## 验证记录

初次 `check-all.sh --all` 的 source、分析及构建阶段通过，随后在锁文件检查失败，日志 `build/non-refactor-closeout-20260929-all.log`；第二轮确认 plugin 锁仍缺失，保留 `build/non-refactor-closeout-20260929-all-r2.log`。失败不记为完整 gate 通过。

最终 `./scripts/check-all.sh --all` exit 0，见 `build/non-refactor-closeout-20260929-all-r3.log`：source、全部主机测试、debug/release lint 与构建、Spotless/detekt、38 份依赖锁、双渠道 debug/release 制品边界和订阅 Runtime 边界均通过。未新增依赖版本；缺锁由 Gradle 生成，不是手写或跳过校验。release APK 编译及制品边界检查只证明本机工程范围，不等于正式签名、同 ID 升级、真机验收或商店通过。本轮未启动模拟器、真机或模型服务；前轮 API36 的 10/10 SAF 与 3/3 Goal 仍是前轮证据。

文档最终更新后再次执行源码门禁：`build/non-refactor-closeout-20260929-source-final.log` exit 0，619 Markdown / 213 HXA / 35 ADR；国际化与敏感信息检查通过，`git diff --check` 通过。

## 归属与剩余边界

开始时架构重构方案已有并行未提交扩写，随后架构索引也发生并行修改；本轮未修改两份文件的方案内容；仅在 diff 检查发现重构方案末尾多余空行时移除该空行，正文逐字保持。旧 isolate-public-eval-work.py 未执行、未删除。初始 WIP 已保存到 `build/non-refactor-closeout-20260929-snapshot/`，含原始文件、patch 和 SHA。工作树整体仍包含他人改动，不声称 clean。

本轮不提交、推送或发布。HXA-231 的 R1 尚未实现，125/126/190 真实服务与 120/122/121/123 发行任务保持开放。历史截断、探测偶发失败和额外调用没有被文档整理关闭。真机资源、Memory 实际收益、真实用户试用和正式候选完整 P5 需对应输入及当前执行授权。
