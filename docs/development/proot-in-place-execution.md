# PRoot 原位执行

Owner 于 2026-10-21 要求在已授权真实工作区运行，代码和文档留在原处，由主应用读取输出并登记文件引用。此切片不完成 HXA-286/287/288 全部资源重构。

## 当前契约

- `code.linux.run`（含 `bash` 别名）和 `code.linux.job.start` 默认 `workspaceMode: in_place`。聊天请求在规范化和审批前冻结 `workspace` 引用，不跟随后续会话目录切换。运行在现有非导出私有 PRoot 进程，仍共享应用 UID/网络，不是安全沙箱。
- 当前支持现有 App/managed 中可直接访问的 POSIX 目录。主应用经原 Workspace 解析器解析，Runtime 复核允许根和目录身份，环境准备后再次校验；支持存在的嵌套 `cwd`。不挂载数据库、凭据目录或任意模型提供的 Android 绝对路径。
- 原位 `files` 引用必须属于本次挂载目录，保留真实层级、不复制、不改名。跨目录输入需显式文件复制或选择快照，不能凭脚本自动扩展授权。
- 代码和文档直接留在原路径，不要求 `output/`。可在 `artifacts` 声明最多 64 个相对工作区根的文件路径；该参数不相对于 `cwd`。不声明的文件仍存在，可经已授权文件工具发现，不自动遍历整个项目猜测产物。
- Runtime 仅返回有界 stdout/stderr 与文件清单，文件清单包含大小和 SHA-256，不传回文件内容。主应用核对原目录及原文件版本后登记原 `scope:` 引用；未捕获、已变化或不可读的文件标为 `unavailableArtifacts`，不冒充成功登记。登记失败不覆盖文件、不执行补偿命令。观察与登记校验分别有有限时间窗口，取消不伪造完整结果。
- 内部日志和清单仍沿原 ZIP/PFD、原终态记录、收取/ACK 路径传输与恢复。原位归档不包含工作副本、代码或文档正文。内部归档以 `RUNTIME_EVIDENCE` 登记，不作为聊天 `OUTPUT` 文件展示；用户产物仍登记原文件引用。按原 jobId/executionId 收取日志和清单，不重新运行命令。未改变有界日志预览和 `tools.read_result` 的能力，也未新增完整 Runtime 日志查询工具。
- `snapshot` 为显式选择，沿既有输入复制、名称映射、`result.txt` 导入与 `output/` 产物发布约定；不自动回写源文件。原位模式拒绝旧 `output` 导入参数，使用 `artifacts` 登记已有文件。
- SAF 是文档 URI 能力，不能通用转换成 POSIX 路径。原生文件工具仍走 Provider；本切片不提供 SAF 目录仿真、自动物化、自动同步或自动写回。需要 CLI 的 SAF 内容必须明确选取快照输入；URI/FD 能力与目录路径能力分开。
- 取消、失败或应用进程死亡不回滚已经修改的真实文件；清理作业临时目录不删除原位工作区。UNKNOWN 不重发，旧实例回包不改变新调用的目录绑定。权限、执行容量、租期和原调用身份继续沿现有路径校验。

## 验证边界

原位执行主体已通过 `scripts/check-all.sh --all`。首次最终复核遇到长驻 Gradle JVM 堆内存耗尽、结果转发挂起；确认仅涉及本轮进程后终止，并以单次 daemon、单 worker 重跑通过，没有将挂起记为成功。内部归档展示分类的最后修正另以 Debug 门禁验证，不重复运行 R8。

日常默认 `scripts/check-all.sh` 运行 Debug 主机门禁；显式 `--all` 保留 Release/R8、发布烟测和产物检查，CI 发布检查不变。Debug 通过不代表发布验收。门禁入口的 7 项测试已通过，覆盖默认 Debug、失败传播与显式完整检查。受影响 JVM 测试及 AndroidTest APK 编译结果记录在本地 `build/` 日志。构建与测试结果以当前实际命令结束状态为准。原位实现阶段未请求设备验证，当时未操作设备、账号或 Git 提交；后续所有者授权的补充验收与提交见下方收尾记录。

主机检查不能证明 Android 上实际 PRoot 挂载、跨 Binder 交付、Room 登记、覆盖更新和页面/进程重建已通过；需要后续明确授权的设备验收。显式快照的跨来源层级映射、完整工作副本迁移矩阵及通用完整日志读取仍属后续边界。

## 本轮主机结果

- `GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1" scripts/check-all.sh`：最终 Debug 门禁通过，包含源检查、静态分析、测试、Debug 打包和产物边界。没有运行应用 R8/Release 打包。
- `./gradlew spotlessApply :runtime:proot-app:assembleDebugAndroidTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`：编译通过；测试 APK 未安装、未执行。最终另由 Debug 门禁验证格式检查。
- App Consumer/Developer JVM 报告分别为 1453/1609 项，各有 5 项条件跳过，失败和错误均为零；PRoot core/IPC/app 分别为 189/45/31 项，全部通过。两个 App 变体存在共享用例，不将总和当作独立行为数量。
- 核心新增覆盖目录身份、原文件操作、链接与越界、引用大小与传输大小区分、取消、观察超时、缺失文件、冻结工作区和产物声明边界；真实 Android 挂载与 Room/Binder 链路仍未做设备验收。

## 后续补充验收

Owner 后续授权完成结果日志、模拟器与真实模型收尾，验证中修复 Android 可变目录 creationTime 见证及取消后日志交付问题。原位/Binder/Room、失败日志和原调用不重发等已执行范围见[结果日志收尾](result-log-closeout.md)；上文的“未做设备”只描述原位实现阶段，不覆盖这次后续验收。
