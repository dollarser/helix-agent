# 独立文件管理首页（2026-10-04）

## 范围与原因

所有者要求文件功能作为独立文件管理器，首页不随会话增加或切换而变化，会话工作空间作为辅助功能。
现有 `FilesHome` 将 `FileManagerService.sources()` 的每个工作空间都平铺为存储卡片，快捷入口又固定为应用目录的 input/work/output；位置切换菜单也平铺同一列表。这是发现入口混淆，不是需要删除会话文件的理由。
决定已更新到 [ADR-WORKSPACE-002](../../adr/workspace/002-manual-files-and-recovery.md)。

## 实现

- 首页固定常用文件夹：下载、文档、相机、图片、视频、音乐，分别打开手机共享存储下的 Download、Documents、DCIM、Pictures、Movies、Music。它们是目录快捷入口，不是全盘类型索引；不隐式创建不存在的目录。
- 存储位置保留手机存储、稳定的 Helix 本地文件区、实际授权的 SAF / Advanced 来源；会话工作空间从此列表和位置切换菜单移出。用户添加或撤销位置仍正常反映实际授权。
- 单一“会话工作空间”辅助入口展示可搜索的目录列表，按真实 scope ID 保持身份；相同标题仍显示不同标识。采用延迟列表，不把全部卡片堆入首页。
- 会话目录根返回到工作空间列表，再返回文件首页；进入二级列表或刷新来源不会自动切换到另一个会话目录。来源消失时关闭旧操作并回到安全的本地入口。
- 手机目录入口复用实时授权流程，授权往返保留所选路径，拒绝和加载失败在首页可见，取消继续传播；手动授权不改变 Agent 权限。
- “本地文件”明确命名为“Helix 本地文件”，避免被误认为手机存储；手动导入说明明确目标为该文件区的 input 目录。

## 常用操作范围

沿用既有实际文件后端的浏览、当前目录搜索、名称/时间/大小排序、列表/网格、新建文件夹、多选、重命名、复制、移动、删除、预览、详情和分享。SAF、共享存储和 Root 仍按实时授权与后端能力执行；只读来源不显示可写能力。工作空间回收站、外部永久删除确认、同名冲突处理、传输取消/部分结果及恢复记录保持原契约。

本次不新增云盘、全盘内容索引、压缩解压或后台扫描，也不将每会话目录合并成同一物理目录。会话文件创建和绑定仍遵循工作目录 ADR。

## 验证

```text
./gradlew :app:testDeveloperDebugUnitTest --tests '*FilesHomeStateTest' --tests '*FileManagerService*Test' :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:compileDeveloperDebugAndroidTestKotlin :app:compileConsumerDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

- JVM：80 项通过，0 失败/跳过。首页状态 4、文件服务 33、SAF 10、传输 28、Root 5。
- 两种 Debug APK 构建、两种 AndroidTest Kotlin 编译、Spotless、detekt、文档与差异检查通过。
- 新增主机回归覆盖 100 个会话不增加首页位置或改变当前目录、工作空间返回层级、来源删除后的旧操作关闭、辅助导航不自动选会话。
- 新增界面用例覆盖大量工作空间仅有一个首页入口、设备目录快捷路径、工作空间搜索及返回；更新既有独立操作、面包屑、导入导出和主题测试的入口与名称断言。
- 首轮静态检查发现位置栏函数超过长度限制，提取独立位置菜单后复跑。

设备状态：`not requested`。本次文件功能请求未要求设备验证；上一轮菜单模拟器授权不扩大到此次任务。界面用例仅编译，未执行，未将本次文件版安装到日常模拟器。未使用外部账号或模型服务，未提交/推送。

## 随后授权的安装交付

所有者随后要求安装到模拟器自行测试。已备份原 APK，以覆盖安装方式更新日常 `Helix_API_36` / `emulator-5554` 的 Developer 版，未执行清除数据。安装成功并打开文件首页，截图确认常用文件夹、手机存储、Helix 本地文件及单一会话工作空间入口。安装包 SHA-256：`815c7b747083c7a7179a626d3630ba1eae7a44eb4d2fc0cbd57af934bfb6f3bf`。

证据位于 ignored `build/files-home-install-2026-10-04/`。本次仅安装及首页显示检查通过，用户操作验收仍待反馈，未运行完整文件设备回归。
