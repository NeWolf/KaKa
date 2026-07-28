---
name: MIUI 后台启动 Activity 的可靠策略
description: KaKa 项目里从无障碍/前台服务拉起 QQ 等第三方 App 时的组合策略
type: feedback
---

从 AccessibilityService 中 `service.startActivity(第三方 App)` 在 MIUI 上会被后台启动限制拦截或静默失败。用户明确要求"先回到自己的 app，然后发送"。

**Why:** 实测三点结论：
1. fullScreenIntent 只有锁屏时才强制跳 Activity；未锁屏时只是普通通知。
2. `service.startActivity(RelayLaunchActivity)` 即便先 HOME 让飞书退出，仍会被 MIUI 拦（日志：`路径 A 结果 relayReady=false pkg=com.miui.home`）。
3. 唯一稳定生效的是**在桌面模拟点击 KaKa 图标**——系统视作用户操作，不受后台启动限制。

**How to apply:** [`replyWithQQ()`](app/src/main/java/com/newolf/kaka/executor/PunchTaskExecutor.kt:592) 的 A→B→C→D：
- A: HOME + `service.startActivity(Relay)`（常被拦，不能依赖）
- B（主力）: 图片路径写 [`PendingShare`](app/src/main/java/com/newolf/kaka/PendingShare.kt)，`clickDesktopIcon("KaKa")` 点桌面图标；`SettingsActivity` 的 onCreate/onNewIntent/onResume 都会消费并转发 QQ。
- C: fullScreenIntent 兜底
- D: 点桌面 QQ 图标，仅打开 QQ 让用户手动分享。

其他：`RelayLaunchActivity` 转发后延迟 ~1.2s 再 finish 避免"还没完全前台化就退出"；分享 Intent 同时设 `component=com.tencent.mobileqq/.activity.JumpActivity` + `setPackage` 避免弹分享方式选择器；MIUI 桌面 pkg 是 `com.miui.home`。