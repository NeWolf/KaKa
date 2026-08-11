- [MIUI 后台启动 Activity 的可靠策略](feedback_miui_background_start.md) — KaKa 项目里从无障碍/前台服务拉起 QQ 等第三方 App 时的组合策略

- [pad-screen-is-portrait](project_pad_screen.md) — KaKa 项目中用户的 pad 是竖屏 2136×3200，不是横屏；换算坐标比例前必须先确认 widthPixels/heightPixels。

- [无障碍点击优先使用节点 bounds 中心，不要用硬编码坐标](feedback_click_use_bounds_center.md) — KaKa 项目中，当 AccessibilityNode.performClick 返回 false 时，必须用该节点 bounds 的中心去 tap，禁止 fallback 到分辨率百分比硬编码坐标
