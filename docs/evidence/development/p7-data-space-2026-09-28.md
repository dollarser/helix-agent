# P7 分类占用与孤立正文清理边界

日期：2026-09-28。基于 `fe4952c7` 后的本轮增量，不代表 P7 整体、真机或发行验收。

## 交付

设置新增用户主动打开的只读占用说明：记录与大正文（共享 Room 数据库及 WAL/SHM/journal、`helix-content`）、私有工作目录/产物/元数据（`workspaces`、`workspace-metadata`）、Memory（`memory`）。同一私有目录不会按会话绑定重复扫描；不遍历外部绑定，不读取文件正文，不上传路径或内容，不新增批量删除入口。

数字是逻辑文件大小，不是应用总占用、每会话独占大小或可释放空间。每类最多处理 10,000 个节点、深度 64；只读取 metadata，不跟随软链接。达到边界、跳过链接/特殊节点或子节点读取失败时标记部分统计。缺失顶层目录可记空，取消传播，失败可刷新，关闭取消统计。统计不是原子快照，写入期间大小可能变化。

说明明确：删除会话保留工作目录，仅移除记录及无引用正文；数据库不保证立即缩小。Workspace 清理仍由原有入口检查引用与在途任务；Memory 仍逐项确认；模型沿用模型页；JSONL 不是完整文件备份。

## 发现并修复的真实缺陷

旧 `StorageGarbageCollector` 的 `walkTopDown` 会跟随目录软链接。新增合成反例在修复前失败：`external file must survive root symlink`，证据 `build/p7-data-gc-baseline.log`。失败发生在实际删除了外部合成 `.tmp` 文件之后，不是测试预期推断。

修复拒绝软链接根，遍历不进入软链接目录且只处理不跟随链接的普通文件；删除前再次检查根到文件的祖先链。新增覆盖根/content/嵌套目录链接，以及引用查询期间父目录被替换的情况。原有引用保护、保护期与正常孤立/临时文件清理测试保留。普通路径检查不构成跨进程原子文件系统事务，不宣称抵御任意并发特权篡改。

## 验证

主机完整通过：`core:storage` unit、consumer/developer app unit、lint、debug APK、AndroidTest APK、`spotlessCheck`、`detekt`。记录 `build/p7-data-space-gates-final.log`；扫描器 5/5、GC 7/7。修正设备测试文本断言后，双渠道 AndroidTest APK 与格式检查再次通过，记录 `build/p7-data-space-test-rebuild.log`。

首次 developer API36 2/3：界面正确显示 `Memory files: 2 B · Partial count…`，测试的 `assertTextContains` 未指定 substring 模式，导致按整个文本匹配失败。只修正字符串匹配方式；保留数量、失败重试、重开刷新和可见性断言，生产代码不变。初次证据：`build/p7-data-space-developer-20260928/`。修正后 developer 3/3（10.377 s），证据 `build/p7-data-space-developer-r2-20260928/`。

consumer 3/3（10.991 s），证据 `build/p7-data-space-consumer-20260928/`。均为 API36 arm64-v8a、4 GiB / 4 cores、400 dpi 的独占只读 AVD；UI 测试覆盖 2 倍字体，正式导航分别打开全部生产分类和既有诊断报告。本轮三次模拟器运行（含首次失败）的 `closed.json` 均记录正常退出。只使用合成数据，不调用真实模型或外部账号。

最终 APK SHA-256：

- developer app：`c40751878d8fa0cd077552f193c6f05fa6b9c3e050341c937f00a4519a32188d`；test：`43fd5567088e409bfefe0a830b162a701a5b96e8550499b910dd1964639c308c`。
- consumer app：`ed0ecf9ae25022b96f459529ddb8bf2ebd6b389d1b2d9e0828408bbc17e571b6`；test：`73b60e258499509ba28a393b38d4273753d09866d667bad79f99e2a4141e848e`。

`check-all.sh --source`、`git diff --check` 通过；源检查记录：`build/p7-data-space-source.log`。工作树定向增量验证与干净源码正式模型基线分开记账。

首次主机检查发现新增 DI 行使 `DefaultAppContainer` 超过现有大小门槛，及删除前检查返回分支超标；将既有无状态本地化 helper 移出类（保留同一 application context）并简化路径检查，没有放宽静态规则。设备测试首次编译漏写导航枚举 import，已修复。

## 剩余边界

不统计完整应用空间，不估算共享文件可回收量，不自动 vacuum 数据库；模型发布中断临时文件、真机存储压力及长稳尚未完成。历史输出截断、模型偶发多余调用继续开放。当前没有重跑 SGLang 正式系统基线。
