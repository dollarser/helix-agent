> 当前消息操作：用户消息仅编辑/复制，编辑不创建分支；模型回复保留创建分支。详见 [消息操作修订](MESSAGE_ACTIONS.md)。

> 会话入口专项重设计：先查看 [可交互原型](conversation-entries.html) 与 [职责、证据及实现定位](CONVERSATION_ENTRIES.md)。它是待接入方案，不代表现有 Compose 已完成；实际浏览器渲染状态见专项记录。

> 当前语音交互已按用户要求改为识别结果直接进入输入框，结束后原处编辑。此前弹窗预览/加入草稿描述以 [语音输入修订](VOICE_INPUT.md) 为准。

# Helix 全应用 UI 与交互原型

打开 [index.html](index.html)。它已内嵌所有 CSS/JS，支持本地 `file://` 打开，无需构建、HTTP 服务、安装或账号。也提供 [standalone.html](standalone.html) 单文件副本，方便在允许本地 HTML 的环境打开。入口默认是会话，左上账号、底部设置沿用获认可的抽屉方向。

这是**现有能力的全产品视觉重设计提案**，不是当前 Compose 的截图复刻，也不是功能或设备验收。页面依据当前工作区源码（含另一任务的在途修改）盘点；未修改这些源码。55 个评审路由是页面、子页和从原对话框展开的设计视图数量，不声称 Android 有 55 个 NavHost 目的地。原始导航包含 14 个 ShellDestination，另有二级路由、Activity、tab 和模态框。

顶部深色条是画布外评审工具：切换浅/暗、手机 390/自适应宽屏、打开目录、复位。场景目录列出 55 页面和 21 专项场景，可为当前内容页切换正常、加载、空和错误。聊天的流式/录音/审批使用专项场景，避免用通用列表错误替代真实交互。

推荐评审顺序：

1. 空白会话 → 输入草稿 → 添加附件/引用 → 选择模型 → 保存 → 发送 → 停止 → 消息操作/分支。
2. 场景“模型 → 登录 → 返回” → 选内置模型 → 使用 `demo@example.invalid` 和任意至少 6 位的**演示密码** → 登录 → 返回原搜索 → 保存。也试取消、注册密码不一致和账号过期。不要输入真实凭据。
3. 录音场景 → 输入框实时识别 → 结束 → 原处编辑；再试取消和麦克风拒绝。语音与播放均为视觉模拟，不使用麦克风、不发声。
4. 工具审批 → 允许/拒绝；未知副作用 → 核对事实。Goal 暂停/继续、计划审阅与规则启停分开。
5. 从抽屉访问项目、工作、工具、插件与设置；检查返回链、搜索筛选、表单和删除取消。

数据在页面内存中，刷新或“复位数据”还原。没有后端、网络请求、真实导入/导出、剪贴板、系统分享、删除、安装、支付、订阅调用或文件改写。动态字符串做 HTML 转义；CSP 禁止网络连接和表单提交。终端输入只作为字符串显示，浏览器地址只更新模拟网页，不导航外部站点。

文档：

- [页面与交互覆盖矩阵](COVERAGE.md)：逐页来源、路由、状态、流程与剩余边界。
- [设计规范与 Compose 实施](DESIGN.md)：tokens、组件、布局、契约、映射和实施顺序。
- [验证记录](VALIDATION.md)：实际执行的检查与未验证限制。

维护：以 `catalog.js` 的 PAGES/SCENARIOS 为页面清单；`app.js` 管理会话模拟状态及导航，`details.js` 管理 Provider/设置及高级交互，`styles.css` 管理 tokens 和布局，`shell.html` 是维护用外链资源壳；`assemble.cjs` 生成内嵌资源的 index/standalone 两个交付入口。修改这些文件后同步生成单文件副本并复跑检查。`verify.cjs` 需要现有的 linkedom DOM 测试库；运行时通过 `DOM_MODULE` 指定其位置，不要求给 Helix 引入 npm 依赖。

```sh
node --check docs/design/helix-ui-prototype/app.js
node --check docs/design/helix-ui-prototype/catalog.js
node --check docs/design/helix-ui-prototype/details.js
node docs/design/helix-ui-prototype/assemble.cjs
DOM_MODULE=/path/to/linkedom node docs/design/helix-ui-prototype/verify.cjs
DOM_MODULE=/path/to/linkedom PROTOTYPE_BUNDLE=standalone node docs/design/helix-ui-prototype/verify.cjs
```

浏览器限制：本轮已知浏览器工具明确阻止 `file://`，且禁止换浏览器或通过转发地址绕过。未启动 HTTP/HTTPS 转发，未更换浏览器。单文件入口供用户在自己允许的浏览器环境打开；本轮没有自动截图，不能把离线 DOM 通过称为视觉通过。

第二版在相同 55 视图中补齐了高级表单，而不是增加空入口。对照变化见 [第二版补齐清单](DETAILS.md)。当前 61 项 DOM 检查通过，21 场景可复位；浏览器视觉仍未验收。

第三轮按真实功能、历史取舍与竞品重新评估了项目、插件、渠道/profile 和新会话权限，并增加聊天成果侧览与精确来源摘要。当前 **55 视图 / 21 场景 / 76 项离线检查**；多文件与单文件分别通过。设计理由与手机/宽屏差异见 [复审说明](REVIEW.md)。

入口兼容性修复：默认 `index.html` 与 `standalone.html` 均内嵌资源，避免本地文件 origin/CSP 阻止相邻 CSS/JS。请用浏览器打开下载后的 HTML；若所在应用仅提供纯文本/源码预览，仍需切换到浏览器打开，不能把该预览当作运行效果。
