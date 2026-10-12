# 输入法、真实视觉与设备稳定性补验

2026-10-12。Owner明确要求推进输入法崩溃、真实视觉、Mobile Use可用后端、有界稳定性及发行条件核对；另明确批准自有实例临时无障碍/悬浮窗权限和只读ADB root词典诊断。当前只有日常API36模拟器，没有实体手机。没有更新日常APK、清数据、迁移旧数据库、操作真实账号或发布。

起点`d3c383c5`；视觉/失败记录夹具提交`24096ba9`。生产Developer APK始终为`76aedcf687853c4ae7241a7b88cc662524a4e2ddbbee5e94add0c325a68725a9`，Consumer为`df979f3e9f040b48405d39d61475076f7c89ad43901d7a4a73043efc579853f8`。后续夹具变化和最终结果逐项记录，不把APK编译、准备像素或Turn终态升级为业务成功。

## Gboard故障与替代环境

故障实例：自有只读`Helix_HXA271_API36`、API36 arm64。Gboard `15.1.08.726012951-preload-arm64-v8a` / `163542534`。Helix未运行时，Android设置搜索框也触发AsyncIme SIGSEGV / 0xa8，栈位于`libintegrated_shared_object.so`、`HmmPinyinQwertyDecodeProcessor`，BuildId `b7804802c7793125`。同实例切英文可显示键盘；移除再添加简体中文仅在自有副本操作，现有拼音夹具仍1/1失败、23.565s。不能把这个结果覆盖此前Owner确认中文正常或HXA-265的历史通过。

只读root诊断只枚举Gboard公开下载资源，选择含`pinyin_bigram`的中文包，读取文件大小与SHA-256；没有读取账号、个人词库、prefs或凭据，没有修改词典。故障实例70文件均为0字节，均为SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。健康`Helix_Main_Verify_API36`与新空白实例的同名70文件均非空、总计15,510,620字节，逐项名称/大小/哈希一致。健康实例同一Gboard版本在系统搜索框实际触摸输入nihao、出现你好候选并提交，XML独立核实文本你好。空资源与解码器崩溃及健康对照相吻合，是直接环境缺陷；具体截断的上游机制没有证明，不声称所有同版输入法或Helix存在此缺陷。

原故障AVD及其数据保留，未在仅读授权下删除缓存。新建空白`Helix_Validation_API36_20261012`仅使用既有官方API36系统镜像，不涉及其他数据库；首次默认分区需要12 GiB而可用约8.7 GiB，启动失败，没有执行设备测试。只把尚未创建userdata的新AVD分区配置为4 GiB后启动。初次添加语言后，中文资源仍在下载，拼音夹具两次因候选缺失失败而非SIGSEGV；下载完成后以普通shell重启Gboard进程，夹具1/1 passed、30.268s。BasicTextField及真实ConversationComposer都经历拼音组合、你好候选点击和准确中文提交，发送次数为0；没有注入中文字符串替代拼音。

随后新AVD冷启动复验拼音1/1 passed，详见`build/validation-2026-10-12-healthy-mobile-final/`。新环境保持host keyboard=yes、show_ime_with_hard_keyboard=1、简体拼音617035939。root诊断的fresh/fault/control三实例均记录恢复UID2000，全部自有进程最终关闭；没有启用持久root或修改日常输入法。授权准备参照[Google的Gboard语言设置](https://support.google.com/gboard/answer/7068494?co=GENIE.Platform%3DAndroid&hl=en)，实际候选/提交是就绪判据，不能由已勾选语言推导通过。

原始证据：`build/validation-2026-10-12-ime-owner/`（无Helix系统设置对照）、`ime-repair/`（保留失败）、`ime-control-older/`（健康实际组合/提交）、`ime-root-fault/`与`ime-root-control/`（公开包元数据）、`ime-fresh/`（启动失败）、`ime-fresh-r2/`（下载前失败、正常重启后通过）。它们均使用同一validation日期前缀；诊断脚本在ignored dated/local，原始图像和字典元数据未入库。

## 真实视觉：请求字节、识别、操作与核验

既有无密钥localhost:30008/v1、Qwen3.8-27B；GET/models核实上下文262144。使用实际Provider、Dispatcher、图片披露和逐请求校验。初始`RealToolVisionDeviceTest`1/1 passed、24.304s：真实浏览器截图进入后续请求，模型答复LEFT=red;RIGHT=green。不同于ToolVisionFlowDeviceTest的fake视觉Provider编码验证。

加强夹具把A/B按钮的颜色在运行时设置为相反两种布局：URL及初始HTML只含gray，不披露实际颜色；模型先截图选择红色，再用语义快照获取目标token、实际点击、再次截图。允许工具限于该标签的观察/点击和内容发现；审批仅匹配准确tab的截图/点击。独立读取实际document.title核实A:1/B:1，要求一个完成的click、所有本次Turn完成及至少两份工具观察附件。

`build/validation-2026-10-12-wire-left/`与`wire-right/`各1/1 passed，分别实际选择A/B；后者操作链为screenshot→snapshot→click→screenshot。临时本机127.0.0.1观察器只转发无密钥合成请求到原Qwen服务，记录图片字节大小/哈希/HTTP状态，不记录正文、头或凭据，不新增生产代理/服务。含模型能力probe的两组共35请求、13带图请求、4种像素哈希，全部HTTP200；这些计数包含probe，不能称为纯任务调用数。

同提交`24096ba9`固定Developer组合14/14 passed、81.287s，`build/validation-2026-10-12-fixed-developer/`，测试APK `082cf675873761e0f4a7ba8fee7d857cd6b9e54b769407bcf59ae4178e133dc9`。其中真实视觉任务再次正确选择B，持久附件hash `d14afcfb63b8631136e062ec0ada8ebae5eeb89acf47bcf2060f1c0ec99d2ac5`与实际请求解码后的PNG逐字节hash相同（4936字节，匹配8个请求，均200）。本夹具点击只更新document.title，所以前后截图像素可相同；效果核验来自独立DOM事实，不声称截图显示了新业务结果。其余为9项原贡献/图像编码/Skill身份及4项真实socket停止、断流、旋转回归。单次布局结果不证明普遍视觉准确率；没有用文本/节点路径替代颜色识别，没有验证其他服务协议或整屏视觉手势。

早期请求manifest日志错误把i字段称作images；该字段实际是输入ID，已改为只记录modelCall ID/usage，图像传输证据来自实际请求和附件hash，不能使用旧标签作证明。模型/图片操作均在合成页面，无第三方账号。

## Mobile Use及后台返回

原故障环境首次模型表单0/2：Turn为COMPLETED但独立目标结果不成立，原始失败完整保留于`visual-mobile-left/`，不能等同任务成功，也没有完整最终答复证据来推断模型谎称成功。增加逐调用拒绝/失败理由。英文隔离对照`visual-right-mobile-english/`2/2通过；3次STALE_TOKEN拒绝后模型采用原精确click_match恢复。两组模型调用存在差异，不仅凭英文对照认定全部失败由输入法造成。

健康中文环境`healthy-mobile-final/`拼音1/1及Qwen表单两轮2/2通过，独立测试App核实TEST_DONE:你好 Helix/count1。一轮skills.read(sessionId="")被原schema拒绝，随后正确读取；没有放宽空字符串/权限检查。无障碍实际授权、绑定与执行，测试退出后恢复原服务配置；悬浮窗许可仅限自有副本并恢复。

首次增加的HOME→Helix检查四次只得到首次使用须知图像，虽窗口非单色且会话内部状态保留，仍不是返回会话验收。检查截图后修正夹具：仅在隔离测试标记已完成首次使用，并要求实际可见原会话标题、原会话身份、连接状态和真实截图；该更强复验单独补记，不覆盖原不足。没有修改生产首次使用行为。

最终更强复验`build/validation-2026-10-12-strong-mobile-return/`：API36 arm64 Developer，自有PID13756/serial5626，生产APK仍76aedcf6；测试APK `12cf6eaa65afc9f0c5c032db91053177e984a1b8c17fb08a6d9bbfea6be232da`。实际拼音1/1 passed、16.441s；Qwen Mobile Use两轮2/2业务通过，分别17.301/14.672s，所有工具调用完成。两轮各两次真实HOME→MainActivity，4/4可见精确原会话标题、原openSessionId和CONNECTED；保留四张PNG，人工查看第0轮首张及第1轮末张，实际会话正文/工具/编辑器均显示，无首次使用须知/黑屏。截图ROI采样7/10色只是辅证，实际标题与图像是更强判据。服务配置恢复null、悬浮窗许可恢复default，owned closed记录exit0。本次仅普通无障碍，合成App，无账号、发送或Root/Shizuku引擎操作。

当前Mobile SDK实际8/8 passed、22.329s，`build/validation-2026-10-12-mobile-sdk/`：取消等待后真实gesture引用直到Android回调退出、图像期间浮窗物理抑制与恢复、停止通知、接管、只导航返回、无Helix无障碍时应用浮窗/撤权、会话可见性及事件风暴/旧结果隔离。不是Root/Shizuku真机执行通过。只有模拟器ADB root元数据诊断，不代表App Root执行服务已具备；Shizuku也没有已准备服务或实体设备。

## 有界稳定性与发行条件

Consumer固定`24096ba9`组合16/16 passed、42.637s：原共同9项、4项socket取消/断开/旋转及3项实际HOME、屏幕关闭/返回和Goal显式恢复。`build/validation-2026-10-12-fixed-consumer/`，测试APK `aade96413c4599d71490b9a5d173ebaaf34d10f88a6fa153a56184cfd153434b`。网络故障为真实loopback SSE socket断开，不是假设关闭Wi-Fi或Qwen远端网络已验。

复用原HXA-103/265资源pilot，900秒窗口与FD+8/线程+16/PSS+96 MiB峰值门禁保持，不强制GC。Developer PASS：37循环、含预热304嵌套用例，单PID2819，峰值FD131/线程43/PSS118290 KiB。Consumer PASS：34循环、含预热280嵌套用例，单PID3852，峰值FD133/线程44/PSS137646 KiB。两组各32次host采样中31次成功，结束后1次无可解析PSS已归因记录；fixture设备内门禁是判据，不把缺样当0。Developer原始源身份d3c383c5加当时夹具diff，Consumer为24096ba9；不把两个不同测试APK说成同一制品。两轮均完整结束并关闭自有实例，不代表2h/24h、全部Runtime/模型业务或物理OEM长稳。

本次仅核对发行条件：两渠道applicationId仍com.helix.agent / .developer，release未配置发行signingConfig，当前验证是debug；开发期Room不兼容仍按现行重建约定，不新增生产迁移。120→122→121→123的渠道/主体、稳定签名/升级承诺、同签名候选、账号与商店材料输入仍缺，不push/发布/签名或消耗真实账号。新权限、未知软件和真实通信不属于本次自动授权。

## 当前剩余边界

实际进程死亡另在`build/validation-2026-10-12-real-process-recovery/`执行：WorkspaceProcessRecoveryDeviceTest setup真实Process.killProcess，原PID2504退出；新的verify PID2867，1/1 passed、0.083s，核实Room原清理身份/栅栏及幂等清理。setup的Process crashed是预期切点，不计通过；未扩展为真实Qwen在途进程死亡或所有UNKNOWN矩阵。

最终主机：`validation-2026-10-12-verified-final-r4.log`包含可选实验模块的test、detekt、spotlessCheck、双渠道lint/APK/App测试APK，BUILD SUCCESSFUL、1m14s、1117实际任务。更强夹具首次detekt发现ReturnCount，调整辅助函数表达式后保留原断言，`strong-return-compiled.log`通过44s。中间AGP解析及HTTP等待、offline缺lifecycle-common-java8:2.9.4等缓存失败均保留，停的仅本轮owned wrapper；临时JAVA_TOOL_OPTIONS指定IPv4/10秒HTTP超时后从现有官方仓库正常补下载，没有修改版本、仓库、锁、校验元数据或TLS。不能把configuration-cache错误包装成源码测试失败或跳过门禁。最终source入口206项、835文档、36 ADR、1078源码/2264三语言keys及secret扫描通过；host负向fixture输出FAIL_FUNCTIONAL属于预期输入，不是设备失败。

实体手机补验pending：至少连接已授权ADB调试的手机并保持解锁，明确目标；在已有无障碍/Root/Shizuku状态上执行有界合成测试，新增安全授权另外确认。模拟器结果不关闭HXA-269真机后台黑屏、OEM或完整后端交叉矩阵。原损坏AVD仅读取证，没有原地修复其缓存；可用替代环境已通过实际输入。更强会话返回、实际进程死亡及最终主机补验已执行如上；未操作日常设备，未更改生产执行逻辑或发行制品。
