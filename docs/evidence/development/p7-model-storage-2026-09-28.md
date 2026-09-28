# P7 本地模型占用与下载残片清理

日期：2026-09-28。范围为现有模型安装对话框和私有下载目录，不代表 P7 整体或发行验收。

## 实现与边界

- 安装页展示已识别模型、可续传残片的逻辑文件大小；明确不包含会话、Workspace、Memory、其他应用数据或文件系统实际分配量。
- 清理前显示确认，说明需要重新下载；关闭确认不执行删除。清理只枚举私有 `model-transfers` 目录内 SHA-256 命名的普通 `.part` 文件，不递归、不跟随软链接，不触碰已安装模型和未知文件。
- 清理与下载共用同一互斥锁。忙碌立即失败并允许刷新重试，不排队等下载完成后再删除。确认快照绑定下载修订号、文件集合、大小、修改时间及可用文件身份；变化时要求重新确认。
- 保留单个待续传模型约定。切换模型不再隐式删除旧下载；页面提示先明确清理再下载另一模型。缓存目录被系统回收后可重新建立。
- 失败不伪装成功，刷新重新读取实际文件；没有更改 Dispatcher、权限、effect、Goal、Room 或运行时资产删除边界。

## 主机验证

双渠道 app unit、lint、debug APK、AndroidTest APK，`spotlessCheck`、`detekt` 已通过，记录：`build/p7-storage-gates.log`。`LocalModelDownloaderTest` 9/9，含新增四项：确认清理保留模型/未知文件/目录/软链接、过期确认保留新字节、下载中清理立即拒绝且取消释放锁、切换模型保留残片直到明确清理。

首次格式检查曾因测试长行失败，静态检查因两个保持同一协调 owner 的类达到方法数门槛失败；修正格式并为这两个内聚类添加有说明的局部抑制，随后全量主机 gate 通过。未修改全局规则或弱化测试。`check-all.sh --source` 与 `git diff --check` 通过，源检查记录在 `build/p7-storage-source.log`。

## 设备验证

API36 arm64-v8a、4 GiB / 4 cores、400 dpi，独占只读 AVD `Helix_HXA210_API36`：

| 渠道 | 结果 | 本地证据目录 |
| --- | --- | --- |
| developer | 3/3，13.482 s | `build/p7-model-storage-developer-20260928/` |
| consumer | 3/3，12.472 s | `build/p7-model-storage-consumer-20260928/` |

`LocalModelStorageDeviceTest` 在 2 倍字体下操作真实合成残片：关闭确认保留文件且没有调用清理；确认后文件删除、未知文件保留。另有原有 `LocalModelDialogDeviceTest` 两项验证安装页和失败后保留输入/关闭能力。两台本轮模拟器的 `closed.json` 均记录进程正常退出。

APK SHA-256：

- developer app：`e3e7c49e55823294c549d35ada4f625112c9e6d1e767c1efed3eed787bbe5554`；test：`99a3539d8286789618dde8086e4bd2d625180f94e7eafd403fd1a1a02d9a0044`。
- consumer app：`bba0761414e7d230824e94a65fc5972b0bb9d90606ff69f84bfe8e8b737201f5`；test：`e13cca7855d4d224093b3b5f05e7a7432f4482035df4f73d280b3bd6698b3358`。

源码基于 `8626be6d` 加本次提交的改动；本轮是工作树增量定向验证，不是新的干净源码 SGLang 正式基线。只使用合成文件，没有下载真实模型或调用外部账号，不代表真机或长稳结果。

## 未覆盖

应用总占用、会话/产物/Memory 的分类占用、发布中断留下的模型资产目录临时文件、真机存储压力与长期运行仍未验收。历史输出截断及模型偶发多余调用仍开放；本轮未重跑 SGLang 系统基线。
