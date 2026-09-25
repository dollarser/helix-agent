# HXA 未完成任务

本目录只保存**尚未完成的代码/产品开发 HXA**，不等于全部任务都在当前执行。HXA 用于实现、重构、修复、迁移、验证和发布；纯文档整理/Research 综合不占 HXA 编号，必要时直接留 evidence。

任务状态以 [roadmap.md](../roadmap.md) 的分类和 [status.md](../status.md) 为准：

- `进行中`：当前允许继续实施；真正主线还应出现在 status 的 `In progress` / `Next task`。
- `收尾验收`：实现主体已存在，但仍有外部账号、真实服务、设备或专项证据未闭合；不要重做已交付主体。
- `发行队列`：发布/签名/渠道前置任务，只有进入发行阶段或 owner 明确授权时执行。
- proposed ADR 对应的未来任务只有在 ADR 被接受且 status/roadmap 明确排期后才进入实现。

一个 HXA 完成后，创建 `../../completion-records/HXA-NNN.md`，更新 status/roadmap/index，并删除本目录的任务文件。不要同时长期保留 task 与 completion 两份“当前版本”。与代码 HXA 同步的 ADR/文档更新属于该 HXA；单独的文档治理不另建 HXA。
