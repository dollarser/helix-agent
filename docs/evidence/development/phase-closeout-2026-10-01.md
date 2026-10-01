# 2026-10-01 阶段收尾：回放、OAuth、Core、插件与基础等待

本记录是当日阶段快照，不是第二份实时路线图。后续顺序只维护在 [status](../../development/status.md)，具体未闭合要求留在原 HXA。

## 结论与范围

所有者要求“阶段性收尾，并更新文档，同步状态，记录剩余工作”。本阶段冻结 HXA-233 回放、HXA-126 OAuth 本地增量、HXA-234 R2-A、HXA-235 R3，以及 HXA-236 已实现的基础观察/有界等待。完整 J1 仍有本地衔接缺口；不启动 J2、Project Memory 或 R2-B，不把设备、真实账号、用户内测和发行标为完成。

现有候选完整适用主机复验通过，构建前后源码一致；文档区分本地交付、未实现和条件性验收。Git 仍为未提交工作树，此记录不是 clean commit、tag、可恢复备份或正式 P5。未提交、未推送、未发布，未执行设备或真实服务操作。

## 候选身份与改动归属

- 基线：`main@05e910039e03095d98649d96a9e64a3a71bcb078`，不是单凭该 HEAD 就能复现的候选。
- 入场 Git 完整文件口径：280 个受跟踪文件变化、254 个未跟踪文件，其中 113 个在 `scripts/debug/`；暂存区为空。数值是本轮修改前快照，包含迁移、新增测试、历史及并行工作，不是本任务独占改动数量。
- sourceManifestSha：`d0cea91edaf5053011d0ad4d627d15a5372d90775a8ebbc409e789745bbd4ae8`。覆盖 App/Core/Provider/Runtime/Extensions/Tools/Feature/Prompts/Testing、Gradle/config/evals、根构建配置、AGENTS 与所列验证入口；精确范围见验证脚本 sources 数组，不包含本次文档或全部临时脚本。
- 完整清单：`build/phase-closeout-2026-10-01/source-before.json`、`source-after.json`，比较一致。清单仅校验源码身份，不包含恢复源码所需的内容。
- 并行 FFmpeg 预集成及其他设备调查材料保留，不接入、不删除、不声称由本阶段验收。未批量暂存、清理、重置或覆盖他人 WIP。

### 同候选 Debug 制品 SHA-256

| 文件（app/build/outputs/apk/ 下） | SHA-256 |
| --- | --- |
| consumer/debug/app-consumer-debug.apk | `e7bdb117ba065a446f5e047d9e3cd6f2f67868dfb789593d5a03bbd17662c122` |
| developer/debug/app-developer-debug.apk | `559f217c07d5ebc9ae33e0b7b86dfbf416396b78e2dade88e5bad9d2ea257d4c` |
| androidTest/consumer/debug/app-consumer-debug-androidTest.apk | `5595372629ce6e23ed85cb2a272d775cf71f08c09e5e2fd692093560beb0a006` |
| androidTest/developer/debug/app-developer-debug-androidTest.apk | `a6a6ccd9ed7cec74f015f209ffad7e7ddd93a2f5bcc9fd141725a5192dccb6c1` |

现存 unsigned Release APK 未在本次重建，不用旧文件冒充签名候选。正式 P5 仍需 clean 提交、同候选 APK、设备和模型条件。

## 分项阶段交付

| 范围 | 阶段结果 | 保留边界 |
| --- | --- | --- |
| [HXA-233](../../development/tasks/HXA-233.md) | 引用感知回放清理、分支/在途保护、存储反馈本地交付 | 8 项设备场景未运行；未知归属保守保留 |
| [HXA-126](../../development/tasks/HXA-126.md) | OAuth 元数据、显式 DCR 后备、归属/回调修复本地交付 | 两家真实服务、新 UI、自有 HTTPS/App Link/签名条件 |
| [HXA-234](../../development/tasks/HXA-234.md) | 唯一 Core Loop、中立端口、有界上下文压缩迁移本地交付 | 9 项新增 Room 用例与真实轨迹未运行；不等于 R2-B 收益 |
| [HXA-235](../../development/tasks/HXA-235.md) | 插件安装/选择/更新/停用/修复本地交付 | Room 与实际设备用户旅程未运行 |
| [HXA-236](../../development/tasks/HXA-236.md) | 基础 join、原身份查询、独立停止等待、原工具结果回填本地交付 | 统一观察上下文、可信类型进展判定及联合/设备验收开放 |

原分项证据和早期失败保留。J1 的准确实现、回归及未实现项见[验证记录](hxa236-job-observation-2026-10-01.md)。没有创建宣称全部完成的 HXA completion，也没有为纯文档收尾新增 HXA。

## 本次集成复核

| 检查面 | 当前核对结果 | 不作出的承诺 |
| --- | --- | --- |
| completion 与审计 | 复用唯一 Dispatcher/Scheduler；发布前再验原绑定/权限，观察配额延续至结算 | 不覆盖全部并发时序、设备进程死亡或存储故障 |
| 取消和查询资源 | 观察取消不投递 Job cancel；未返回 IPC 仍占容量；查询与用户控制分槽 | 无响应 Runtime 不保证已经收到或完成取消 |
| 插件撤销 | 方法接线存在；重新启用产生新绑定，缺投影不冒充就绪；共享内容保留 | 不替代实际安装/卸载设备旅程 |
| J1 上下文与进展 | 两处本地契约缺口明确留在原 HXA | 基础 await 可用不等于整个 J1 完成 |
| 工作树归属 | 保留迁移、新增源码/测试及并行材料 | 不是 280 个受跟踪变更的完整逐行审计 |

本轮未修改生产源码，未削弱、跳过或删除测试，未增加产品框架、数据库、权限或后台政策。

## 实际验证与复验入口

```bash
bash scripts/debug/2026-10-01/validate-stage-closeout.sh
bash scripts/check-all.sh --source
bash scripts/check-all.sh --artifacts
git diff --check
```

第一条已实际退出 0：989 个 Gradle tasks，19 executed / 970 up-to-date，包含 test、detekt、spotlessCheck、双渠道 lint/Debug APK/AndroidTest APK。日志与退出码在 `build/phase-closeout-2026-10-01/host.log`、`host.exit`。这是增量整合检查，不宣称所有测试强制重跑。文档同步后的实际检查结果见下节，不从旧日志继承。

## 文档同步后的最终检查

本次新增两份阶段证据，更新 status、roadmap、candidate-decisions、release-readiness、internal-pilot、terminal、Harness 方案以及 HXA-126/233/234/236，共 13 份文档；另新增一个 host-only 验证脚本。HXA-235 原任务已经处于收尾验收且与新证据一致，未重复改写。

同步后 `check-all.sh --source`、`check-all.sh --artifacts`、`git diff --check` 与 `spotlessCheck` 联合执行退出 0。源码检查实际覆盖 685 Markdown、219 HXA、35 份现行 ADR、1941 个多语言键及秘密扫描；双渠道组件/进程/UID/payload/launcher 和 Consumer 订阅排除检查通过。格式检查退出 0，6 tasks，4 executed / 2 up-to-date。日志分别在当前 phase 输出目录的 `source.log`、`artifacts.log`、`format.log`。

文档同步后再次生成 `source-final.json`，与完整主机复验后的 `source-after.json` 比较一致，生产候选没有因本次文档工作改变。直接 Git 查询仍为 `05e91003`、暂存区为空；卫生检查工具一次错误报告非 Git 项目，未据此覆盖直接 Git 事实或删除文件。阶段源码指纹不覆盖文档；追加本节后再做最终文档/格式核验，输出另存 `source-final.log`、`format-final.log`，避免覆盖前一次结果。

## 阶段保存与后续交接

后续首先处理 HXA-236 统一观察上下文与可信类型进展判定，沿已有管线补测试；之后 J2-1 AUTO → J2-2 用户后台按钮 → 完整 Project Memory。R2-B、工具发现/Mobile Use 增量按固定实验或真实任务缺口决定，不重做 R1/R2-A/R3 主体。

条件性工作由原任务承接：HXA-232 完整故障矩阵和真实恢复效果、233/234/235/236 设备补验、125/126/190 真实服务、同候选 P5 和独立用户内测、指定 OEM/资源长稳及 16 KiB 真机；发行依次 120 → 122 → 121 → 123。缺账号/设备仅阻塞对应项，不伪造通过。

当前未形成 Git 提交。授权提交时先核对源码指纹及全部新增源码/测试归属；回放、OAuth、R2-A、R3、J1 是逻辑审查组，同一文件可能跨组，不能按目录机械拆分制造不可构建的中间提交。可独立构建的组分开，强耦合迁移用集成提交。临时脚本区分可复用验证入口、一次性迁移脚本和他人材料，不直接 `git add .`；提交后记录新 SHA 并验证。不经授权推送、发布或消耗真实账号。
