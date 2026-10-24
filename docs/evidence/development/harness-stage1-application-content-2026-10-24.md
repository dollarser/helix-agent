# Harness 阶段 1：内容与应用命令 checkpoint

日期：2026-10-24。起点为 v0.0.7 发布记录提交 `55eb9579`；本记录随对应重构源码本地提交，不改发行标签或附件，不推送。它不是完整 HXA-287 或发行验收。

## 已落实的边界

- 扩展贡献必需指导、Skill 目录和原子激活内容的版本化事实；删除旧 `instructions()` 聚合和插件名称特判。内置帮助资格来自可信登记，导入元数据不授予资格。
- App 的 `application/context` 映射事实，`infrastructure/prompt` 提供打包模板。唯一 Core ContextCompiler 按实际历史、schema、图片、输出预留和校准共同选择完整可选项；保留必需指导与激活正文/参数，不切碎合法调用历史。受控读取提示只引用当前实际可调用工具。
- 记录实际发送 SYSTEM 内容的身份，包括摘要请求；省略、来源、版本、读取提示和成本可审计，正文及激活参数不进入审计。修复 Room 旧分支跳过摘要身份记录的问题。
- `application/agent` 的提交命令真正拥有准备、原准入与启动接线；普通提交、队列和恢复检查使用同一入口。停止命令保留持久化后发信号、原 live owner 和物理退出边界。不存在“缺少会话就使用当前页面”的回退。
- Goal 应用协作者及配置 fingerprint 随职责迁移，当前消费者和测试/runner 引用更新；旧路径删除，不保留转发别名。归档、Provider/model 在准入事务内重验；拒绝为类型化事实，由 UI 本地化。

仍由既有 Engine/Room 负责事务、终态与恢复；ChatToolCalls/Settlement 的展示接缝、TurnCoordinator 的运行/持久化混合和完整 Engine 宿主依赖尚待 HXA-287 B/C 收敛。当前不声称仅迁目录就完成中立化。Memory 前缀策略未替换，293-B 实验未执行。

## 主机验证

- `scripts/check-all.sh --source`：294 项宿主 fixture 通过；文档、ADR、国际化、秘密扫描通过。
- 单次 daemon、6 GiB、单 worker，启用项目原有 spikes：`spotlessCheck detekt lintDebug lintConsumerDebug lintDeveloperDebug test :app:assembleConsumerDebug :app:assembleDeveloperDebug` 通过，耗时 4 分 17 秒。配套锁文件、渠道及 CLI Runtime 边界检查通过；这与当前 Debug 门禁组成相同，不是 Release/R8 结果。
- Core Agent 311、Workspace 112、Plugin 91、Skills 60 项 JVM 测试均零失败/零跳过；App Standard 1473、Advanced 1635 项零失败，各保留 5 项既有跳过。两渠道 AndroidTest 编译通过。
- 新增源码依赖检查：Core 不导入 App/Android/Room，插件贡献不导入 App/Core 编排，已迁应用目录不导入聊天页面、Compose/UI 或模板基础设施；旧应用路径无转发壳。它是源码导入检查，不代表进程隔离或完整域迁移已证明。

## 模拟器结果与失败保留

仅 API 36 ARM64 独立验证包，合成资料及 loopback/mock 模型；没有真机、真实账号登录或云模型效果验收。两渠道 APK/测试 APK 与源码输入身份保存在本地 `build/refactor-stage1-2026-10-24/`，不是日常安装包。

| 运行 | 结果及范围 |
| --- | --- |
| A1/内容冻结候选 | 27 项通过、零跳过；停止/取消、Memory 权限、插件撤权和压缩 |
| Memory 实际进程终止/重建 | setup 按预期终止进程，新的 PID 验证 1 项通过，零跳过 |
| A2 Standard 首轮 | 92 项执行，83 通过、9 失败，零跳过；原始结果保留 |
| Standard 夹具修正复验 | 11 项通过、零跳过；覆盖修改的两个测试类全部用例 |
| Advanced 最终候选 | 97 项通过、零跳过；包含提交/停止、receipt、队列、Steer、恢复、Goal、定时任务、review、摘要身份、插件和 Memory |

首轮 3 个新用例在连接测试前创建会话，被现有测试门禁正确拒绝；调整测试顺序后通过。其余 6 个失败在既有定时任务 mock 夹具等待模型就绪时超时：保存目录仍绑定默认 signed-out 身份。仅全 mock 分支通过已有身份接口提供合成账号，增加绑定断言；真实分支和生产鉴权不改。两份生产 APK 哈希未变，仅两个测试源码和测试 APK 更新。不能把原首轮写成 92 项单次全通过，也不把 mock 结果当真实账号验收。

第一次 AVD 启动因日常实例锁失败，未执行测试；改用独立验证 AVD。一次 Advanced 启动前端口占用失败，尚未安装/运行；核查后使用空闲端口。运行器关闭其拥有的临时实例，未关闭/清理日常模拟器，未操作真机。键盘与 IME 可用设置沿原运行器，未将非输入任务扩成中文输入验收。

## 后续与边界

按 HXA-287 继续分离原子持久命令、运行协调与 UI 投影，再迁完整 Engine/driver；不能将原事务 callback 原样暴露到 Core 或新建第二 owner。`core:storage` 当前仍是 Android/Room 基础设施，物理目录重组随真实端口收敛，不借此清数据。

本 checkpoint 未运行新的 Release/R8、真实模型、真实外部分享或 OAuth；阶段最终源码稳定后再集中做适用发行检查。未采纳知识库原型与原有指南改动均排除本次提交。
