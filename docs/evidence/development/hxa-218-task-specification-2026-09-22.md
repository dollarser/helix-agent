> 历史任务规格：保留2026-09-22的授权范围与验收要求；当前交付见[完成记录](../../completion-records/HXA-218.md)。

# HXA-218：会话工作台 UI 与交互重构

分类：开发中。2026-09-22 所有者明确授权在独立 worktree 开始 UI 与交互重构。方案见[审查与设计](../../research/ui-interaction-optimization.md)。本切片实现不依赖214新输入契约的呈现层改造；不把本任务视为214～217或129的完成。

## 范围与验收要求

会话头部减少重复入口；模型/模式保持可见，高级参数按需展开；任务清单按需查看；工具操作紧凑但待审批/恢复保持可见；全局抽屉有分组且全部已有路由可达。

允许模块：`app/ui`、应用导航、默认/简中/英文资源、相应主机/设备测试、验证脚本和文档。沿现有服务执行，不改变存储、权限、Provider、运行/停止、Goal、工具执行或结果身份。底部Tab、产物跨页预览、`@`/`/`及Queue/Steer按后续切片处理，不以空入口假装交付。

## 实施

1. 从同一源码运行完整主机门禁和四象限相关UI基线，记录失败及修复。复制方案文档到工作树，保留主目录未提交文件。
2. 头部保留导航、标题、返回会话和更多；新建/后台任务移入上下文面板。共享可滚动设置面板适配短窗口与大字体，不叠加执行权限。
3. Composer保留高频模式/模型入口，将推理强度、普通上下文占用移到选项面板。保持已有运行锁、发送和停止行为；取消选项面板不改变草稿。
4. 工具摘要收敛为可点击卡片；请求/结果可原地展开，待审批/恢复独立可见。TaskLedger默认摘要，阻塞项可发现，展开原始状态。全局导航按会话、工作、扩展、设置分组。
5. 更新行为测试，覆盖窄屏/大字体、菜单显式选择、取消/返回、运行锁、审批可达与结果展开；截图观察现有真实测试场景，不用构建成功代替交互验收。

## 验证入口

使用JDK17和现有host slot；Gradle workers=2、parallel=false、daemon=false。源码基线和改动后设备各用全新输出目录，独占API29/36 × consumer/developer，严格检查非零用例和真实方法集合。外部账号和物理验收仍独立。

```sh
python3 scripts/debug/2026-09-18/with-host-slot.py -- ./scripts/check-all.sh --all
python3 scripts/debug/2026-09-18/with-host-slot.py -- ./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
python3 scripts/debug/2026-09-18/with-host-slot.py -- python3 scripts/debug/2026-09-22/accept-ui-refactor.py --output build/ui-refactor-devices
git diff --check
```

runner复用现有owned-emulator/严格结果收集器。设备必须在finally仅关闭自有进程。记录APK哈希、方法清单和执行数；不得删除/跳过原测试来通过。回归因布局迁移而更新到新的用户入口，保留原行为断言。

## 交付

本切片通过后建立完成记录并更新本分支状态；原主目录不合并、不推送、不发布。下一切片按方案继续就地预览和输入基础的整合，分别验收。
