# 会话任务、成果与 Git 入口优化（2026-10-04）

## 分析

- 后台执行记录有真实 sessionId，成果文件同样保留来源会话和执行身份。全局页面是汇总视图，不改变持久归属。
- 会话“任务”弹窗原先直接显示全局 backgroundTasks，确实混入其他会话。会话成果本来按会话查询，但无文件时不显示入口。
- 全局成果页原先使用 `!running`，会包含失败、取消和待核查执行，与全部任务混淆。已写出的文件可以独立于整次任务成败存在，不能因此删除文件索引。
- Git 页面使用 `GitWorkspaceReader` 内置 JGit 库，读取当前会话目录的分支、暂存/未暂存/未跟踪文件及差异。不调用原生 git 命令，不依赖 PRoot，不包含 clone、提交、推送、分支管理或自动版本备份。既有仓库发现支持工作目录根或按名称选取首个直接子仓库，并非完整多仓库选择器。
- 全局任务还包含 Goal、后台 Job 和计划审阅；计划定义不都带会话归属，不能把整个全局面板简单套上当前会话过滤后声称完整。会话弹窗继续管理本会话执行，Goal/计划保持各自既有入口。

## 调整

- 侧栏“任务、成果”改为“全部任务、全部成果”，明确跨会话汇总语义。
- 会话弹窗改为“本会话任务”，严格按当前 sessionId 筛选；身份为空时为空列表，不能回退到全局。切换会话重置弹窗状态，打开时刷新持久记录投影。
- “本会话成果”保留在会话内，无成果时也显示零数量入口和原有空状态；不把输入附件冒充成果。
- 全部成果只将 COMPLETED 执行列为完成结果，其他执行仍可在全部任务查看。文件成果继续按真实文件记录展示，并保留来源追溯；全空时显示明确空状态。
- Git 从全局工作分组移到会话更多菜单，名称为“工作目录 Git（只读）”。进入前沿用草稿保存流程，页面固定进入时的会话标题和目录，刷新不随其他会话的状态变化而换仓库。相关决定见 [Git ADR](../../adr/workspace/003-git-boundaries.md)。

没有改变执行归属、授权、任务控制或实际文件数据；没有新增独立 Git 客户端。

## 验证命令与边界

```text
./gradlew :app:testDeveloperDebugUnitTest --tests '*SessionWorkProjectionTest' --tests '*TasksDashboardProjectionTest' --tests '*GitWorkspaceReaderTest' --tests '*GitAreaRegressionTest' :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:compileDeveloperDebugAndroidTestKotlin :app:compileConsumerDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

新增主机用例验证同名不同会话隔离、缺失会话身份不泄露全局列表，以及失败/取消/未知执行不充当完成结果；更新设备用例中的 Git 导航、全局分组和零成果入口断言。

主机结果：44 项 JVM 用例通过（会话投影 2、任务面板 23、Git 读取 15、Git 差异回归 4），0 失败/跳过。Developer / Consumer Debug 构建、两种 AndroidTest Kotlin 编译、Spotless、detekt、文档和差异检查通过。静态检查中发现测试长行及成果页函数长度/数量限制，修正格式并拆出刷新控件后复跑通过。

设备状态：`not requested`。当前请求为分析与优化，没有此次设备测试/安装授权；此前文件版本的安装记录不证明本轮菜单版本通过。设备用例仅编译，未执行。未调用真实模型、远程 Git 或真实账号，未提交/推送。
