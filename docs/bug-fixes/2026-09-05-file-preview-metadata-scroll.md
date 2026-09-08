# Bug Fix: 文件预览与元数据统一发布和滚动

Status: fixed
Date: 2026-09-05
Related HXA: HXA-043
Affected modules: app

## Problem

API 36 文件预览出现 SHA 元数据等待超时；长文本详情中 SHA 没有可滚动的父布局。

## Impact

正文可见时用户或 UI 自动化仍可能无法到达大小、MIME 与 SHA 信息。

## Root cause

原正文单独滚动，元数据是外部 sibling；整个详情内容没有滚动容器。预览和元数据还在 IO dispatcher 分别发布，正文出现不代表完整详情已发布。

## Fix and invariants

后台完成预览、图片和元数据读取后，在 composition dispatcher 一次连续发布；正文与元数据进入同一个滚动容器，保留后台 I/O 和有界预览。

## Alternatives considered

不通过只增加等待时间或移除 SHA 断言消除失败；不把文件读取移到主线程。

## Regression verification

FilesScreenTest.longTextPreviewKeepsTheMetadataReachable 在旧布局报没有 Scroll SemanticsAction 的父布局；新布局实际滚动到 SHA 并断言显示。短文本用例同样断言 SHA 可达；完整矩阵见验证报告。

## Residual risk

初始某次短文本超时无法仅凭旧日志唯一归因为布局或状态发布；本修复覆盖二者边界，但不声称任意负载下 UI 从不超时。

## Related records

- [HXA-043](../completion-records/HXA-043.md)
- [main 验证报告](../development/main-merged-verification.md)
