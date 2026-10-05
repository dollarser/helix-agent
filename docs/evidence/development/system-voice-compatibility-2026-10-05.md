# 系统语音兼容性与故障入口

Date: 2026-10-05
Scope: 所有者无法进行真机验证，要求优先系统服务，暂不提供自建本地或在线语音后端。

## 当前边界

Helix 的语音输入调用 `ACTION_RECOGNIZE_SPEECH` 系统 Activity，得到文字后追加为可编辑草稿；不直接录音、不申请麦克风权限、不自动发送。当前没有消息朗读功能。TTS 只在本轮作为独立的系统能力展示，不能把已安装引擎说成已验证中文可朗读。

应用清单原本已有识别 Activity 查询声明；本轮增加 RecognitionService 与 TTS 服务查询，用于分开诊断系统能力。没有一加 Ace 5 的设备证据，不能断言是缺少 Google 服务、权限还是厂商接口差异。

## 修改

- 设置的通用页面增加“系统语音”，显示系统输入入口、只有服务而没有可打开界面、未发现入口等状态；TTS 独立显示已检测引擎数量与尚未提供朗读的边界。
- 返回系统设置后重新查询，不缓存设备能力结论。系统服务并不保证支持所选语言，提示用户在系统设置检查中文及语言包。
- 语音/朗读设置页面不存在或被厂商限制时，依次尝试输入法/无障碍及系统设置；全部失败显示错误。不绑定 Google 包或手机型号。
- 原映射把所有非 OK 结果当作取消；现在区分平台明确提供的无匹配、录音、网络、客户端/服务端错误。用户取消仍安静返回；部分厂商把错误折叠为取消时，无法凭空恢复错误原因。
- 失败可直接打开同一诊断面板；识别结果仍绑定原会话，切换会话不会把文字或错误投到另一个会话。没有识别 Activity 的设备提示键盘麦克风替代路径，不把只有 RecognitionService 当成可启动界面。
- 系统语音应用可能使用自身网络服务，界面明确说明。Helix 未接入其他语音后端。

## 依据

[Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent) 定义标准识别入口及结果码；[包可见性指南](https://developer.android.com/training/package-visibility/use-cases) 区分应用启动与查询可见性；[TTS 引擎接口](https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine) 定义独立朗读服务和语言数据检查机制。

## 验证

主机 app Developer 单测、双渠道 APK 构建、AndroidTest Kotlin 编译、Detekt、格式、多语言和文档检查。定向覆盖平台错误映射、未知错误不使用陈旧转写、取消不产生草稿、成功只返回文字。AndroidTest 补充真实平台常量与映射的一致性检查，仅编译未运行。

日志在忽略目录 `build/system-voice-final.log`、`build/system-voice-verified.log`。设备和真实服务测试 not requested；未启动模拟器或真机，未录音、未使用语音额度。不能据此宣称一加 Ace 5 的语音识别已恢复。
