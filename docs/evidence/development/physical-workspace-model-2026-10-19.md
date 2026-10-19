# 真机与真实内置模型追加验收

实际执行日期 2026-10-19 UTC，设备时区 Asia/Shanghai。

## 范围与候选

Owner 在工作区/容量与指导精简交付后，明确要求更新文档并进行真机、真实模型测试。仅固定内置网关 `https://api.sunlingzhang.com/v1`、模型 `Qwen3.8-27B` 与公开 `X-Client-Id: helix-android-preview-v1`；无后端 Key、其他 Provider 或付费回退，不推送、不修改 Release。前轮模拟器/主机结果见[原记录](workspace-capacity-and-guidance-2026-10-19.md)，不迁作真机证据。

使用 `45e2f0d7` 成功构建并冻结的 Advanced ARM64 R8，0.0.6/code 1，non-debuggable。完整 APK 哈希、构建输入和签名沿用本地 `build/workspace-capacity-2026-10-19/candidate/provenance.json`；本轮核对实际安装字节一致，非文档输入未变。本次只有文档更新，没有将文档提交伪装为新的 APK 构建。

## 安装预检与当前删除许可

- 实际物理 OnePlus 6T（ONEPLUS A6013）、API34、ARM64；ADB 已授权。连接的日常 API36 模拟器未操作，未代替真机。
- 初始两渠道为 0.0.5/code 1 调试版，签名与候选一致，无活动 Turn 或未回收终端。两库 `user_version` 均为 1，但身份与当前架构不同，按初始保留数据要求停检并报告。
- Owner 随后明确要求“直接覆盖安装”。执行 Advanced `install -r`；ADB 的 incremental 安装被平台拒绝后自动改用 streamed install 并成功，实际字节等于冻结候选，安装前后数据库文件摘要不变。Standard 未安装。
- 首次启动准确显示旧预览库重建页；覆盖安装许可没有被当作删除许可。Owner 再明确“允许清空 Advanced 旧数据库并继续”，才通过当前前台“清空旧数据并继续”按钮重建。没有使用 `pm clear`，不删除 Standard 或数据库外文件。这是经授权的基线重建，**不证明支持旧内部架构无损迁移**。
- 预检 SQLite 查询曾因手机 APatch `su` 消费 `-readonly` 参数失败；改为 `su -c` 后完成相同只读元数据查询。旧会话/凭据内容未导出。Shell 诊断可用不代表本轮测试或改变了 App Root 后端授权。

## 真机终端与目录

精确 R8 候选执行外部独立 UI 测试，保留原有应用测试 APK；本轮新装的外部验收 APK 在结束后移除。

- 单终端 4 项、四终端 4 项检查通过：工具栏同一行、完整触控范围/视口、信息与帮助、现有实例选择及真实键盘显隐。四实例时没有“新建终端”；截图目视核对紧凑顶部与实际终端视口。
- 在零会话/零 Turn 下真实启动四个手动终端，各自一次输入创建唯一合成目录和有界子进程。切到桌面后四个子进程仍在；返回后 A、D 的新鲜检查点确认原 PID/运行 generation、环境变量与 `cd` 后目录，启动标记没有重复。
- 第四终端通过一个关闭动作退出整个子树，其余 A/B/C 保留；原槽位成功启动替代 E，再关闭 E，原三个仍保留。随后 C/B/A 逐一关闭，检查剩余子进程存活；五次关闭 UI 检查通过，全部 `PROCESS_TREE_EXIT`/ACK，未清除 UNKNOWN 冒充释放。
- 文件首页的“工作目录”入口需滚动到下方，首次未滚动定位失败属于夹具输入假设；有界滚动后，真实合成工作区卡片显示“本地文件目录 · 浏览工作空间文件”。没有进入其他目录，也没有把 URI-native 原位重构算作已实现。

终端相关 UI 共 13 项通过，另有会话无传输提醒回归 1 项通过；四次精确输入辅助测试分别通过。当前物理手机为竖屏/原系统字体，未重跑前轮模拟器横竖屏/大字体矩阵。

## 真机真实模型与效果

明确保存内置 Qwen 与低档选择，在唯一新建合成会话中实际完成 4 个 Turn、10 次模型调用：文字 1 次、文件链 4 次、错误哈希链 2 次、Linux 发现/执行/消费 3 次。全部模型调用冻结为固定内置网络目标并完成；没有其他模型调用。App 调用的 `requestId` 未持久化，保留原 null，不虚构 HTTP 请求 ID 或逐次 HTTP 状态。

| 检查 | 实际效果及持久/消费证据 |
| --- | --- |
| 文字 | 实际 SSE 返回唯一预期标记，Turn 与模型调用均 COMPLETED |
| 文件链 | 模型实际请求 `files.mkdir`、`write`、`read(includeSha256=true)`；目录卡按正常界面本次批准，其余依当前会话权限决策。指定文件精确写入 24 字节无换行，独立读取字节/哈希与工具结果及模型最终回答一致 |
| hash 拒写 | 模型仅一次带全零错误 `expectedSha256` 的 overwrite；工具按预期 FAILED，返回 expected/observed 与重新读取要求。模型正确报告拒写且未重试；独立文件内容保持，不把这个预期工具失败记作产品失败或写成功 |
| Linux snapshot | 实际 `tools.search` 后 `code.linux.run` 仅以指定合成文件为输入执行 `cat`，退出码 0、stdout 24 字节。结果含 local-proot、snapshot、真实 guest cwd/输入映射、`automaticSourceWriteback=false`；后续模型请求 manifest 包含原工具消息，最终回答准确区分源目录和 Job 复制快照 |

6 个工具调用为 5 成功、1 个预期 hash 失败；全部对应工具消息进入模型后续请求 manifest。最终不能仅凭 Turn 完成推导通过：独立文件、结果 verified 标志、原生 Job 身份和模型实际消费均单独核对。

收口脚本曾错误假设 Linux 会写通用 `executions` 表，断言失败后先保留现场。源码 `LinuxJobExecution`/`ProotJobBindingStore`/`ProotJobStore` 证明当前持久契约是准备审计与原生 Job journal；按该契约核对原 call/Turn/session、job/execution/input manifest 身份一致，独立复算终态提交哈希相符，状态 SUCCEEDED、exit 0、未过期、已 ACK。没有把空 `executions` 行当退出证明或修改生产实现绕过断言。

## 独立网关检查

另执行主机 `GET /models` 和一次有界合成 `POST /chat/completions`，均 HTTP 200；生成最多 512 tokens、非流式、低档，返回预期标记并 stop。没有重定向跟随、自动重试、私有内容或 Key。当前任务推理总数为手机 10 次加主机 1 次，二者证据分开。

目录声明窗口 262144、档位 `low/medium/xhigh`、默认 `xhigh`、`high → xhigh` alias；这是当前网关扩展字段，不表示所有 SGLang 均有通用档位发现 API，也不证明实际模板/强度语义。客户端契约和未知模型保守策略未因此改写。

## 主机、清理与结果

修正 status 的当前授权、真实结果和旧库条件，HXA-307 的待发布、runtime-assets 不变及早期推理 pending 均明确所属历史阶段；原记录保留，没有重启后续架构队列。`./scripts/check-all.sh --source` 退出 0：Python 276 项、文档 933 份/290 HXA、37 ADR、国际化、秘密扫描通过；最终文档与 `git diff --check` 再核对。生产实现未变，本轮未重新构建 APK 或重跑全量 JVM，不把前轮通过写成新执行。

全部五个本轮终端子树退出/ACK，手动四槽和 Job owner 绑定为空；仅删除逐一核对归属与内容的五个合成终端目录，移除本轮外部测试 APK，预装三个测试包保持。Standard 未覆盖、启动或重建，后半段数据库文件摘要保持；全局字体、旋转和 IME 设置保持。保留合成会话、工具/审计事实及其 24 字节样例供 Owner 复核。已删除本轮拉取的两份重复旧 APK，冻结候选保留。

本轮指定真机 Advanced R8 与真实模型有界验收为 `passed`；无未完成的指定功能检查。其他 OEM/API、物理横竖屏/大字体、存量活动终端覆盖安装恢复、真实低内存/热压/Doze、长稳、Root 后端及强度语义未验，不算通过。没有新推送、Release 或稳定版本迁移承诺。原始预检、输入、JUnit、模型/工具/请求 manifest、原生证明及 `final-result.json` 在忽略的 `build/physical-workspace-model-2026-10-19/`。
