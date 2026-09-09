# ADR-0036: 用户文件管理器的共享存储根目录

Status: accepted
Date: 2026-09-09
HXA: HXA-173
Deciders: Project owner（明确要求共享入口直接打开根目录，缺权限申请）
Supersedes: none
Superseded by: none

## Context

[ADR-0013](0013-standard-store-capability-preserving-distribution.md)要求按渠道真实约束保留完整能力。共享入口只跳转设置不能满足独立文件管理器的根目录浏览用途；现有 Agent all-files 根目录有独立显式范围，不能把手动浏览等同于赋予 Agent 全盘权限。

## Decision

两个 flavor 声明用户手动文件管理所需共享访问权限。API30+ 按用户点击打开 MANAGE_EXTERNAL_STORAGE 授权页，返回重验后进入共享存储根目录；API29 使用受版本限制的读取权限和 legacy storage。每次访问重新检查权限。拒绝保持可恢复 UI，保留 SAF；不得承诺 Android/data 或其他 App 私有目录可读。

仅 FileManagerService 的独立 resolver 增加 user-shared-storage 根目录；Agent resolver、原有 af- 范围、Tool Policy/Approval 均不扩大。复用路径包含检查和拒绝符号链接，首轮根目录支持浏览/预览/分享，不冒充共享目录编辑功能。相同应用内 Workspace 的原有整理功能保留。

## Consequences

consumer 从不声明权限的旧实现发生变化，但不推翻 accepted ADR-0013。商店发布需文件管理核心用途声明和权限审核；未获得审核证据前不能宣称可直接发布。只声明不代表权限已授予，更不代表工具获批。

## Verification

HXA-173 须覆盖两个 manifest、根目录拒绝/授权/撤销/返回、路径逃逸与 Agent 不可解析手动 scope、原有文件回归和真实设备安装。接受本决策不表示这些验证已完成。

## References

- [Android 所有文件访问](https://developer.android.com/training/data-storage/manage-all-files)
- [Google Play 权限政策](https://support.google.com/googleplay/android-developer/answer/10467955?hl=en-GB)

## Alternatives considered

仅 SAF 无法满足 Android 11+ 存储根目录浏览；把整个根目录注册为 Agent scope 会扩大模型权限，未采用。继续保留 SAF 子目录作为替代入口。

## Reconsider when

商店审核明确拒绝该核心文件管理用途，或平台权限/目录限制发生变化时，按实际渠道结果调整；共享根目录修改功能另行验证冲突、撤销与恢复。

## 后续扩展

[ADR-0041](0041-manual-file-management-mutations.md) 基于所有者的新授权扩展首轮只读能力；本记录保留共享根目录和 Agent scope 隔离决定及 HXA-173 的历史验收范围。
