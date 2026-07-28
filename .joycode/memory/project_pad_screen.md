---
name: pad-screen-is-portrait
description: KaKa 项目中用户的 pad 是竖屏 2136×3200，不是横屏；换算坐标比例前必须先确认 widthPixels/heightPixels。
type: project
---

用户手上的测试 pad 使用**竖屏方向**：`widthPixels=2136, heightPixels=3200`（不是 3200×2136 横屏）。

**Why:** 之前根据用户口述的按钮 bounds（x∈[841,1290], y∈[920,1060]）我误以为 pad 是 3200×2136 横屏，把 [`PunchTaskExecutor.clickPunchButton()`](app/src/main/java/com/newolf/kaka/executor/PunchTaskExecutor.kt:690) 里的 pad 分支 x 比例算成了 0.333，实际运行时按钮位置完全错位。用户手动实测校准后正确的 pad 比例是 `xRatio=0.48, y上=0.61, y下=0.694`（对应真机 tap 坐标 ~1025, ~1952/~2221）。

**How to apply:** 
- 涉及 pad 坐标/比例的改动，先确认屏幕是竖屏 2136×3200
- 换算比例时 x 用 widthPixels=2136，y 用 heightPixels=3200
- 需要精确坐标时**要求用户在真机上实测**，不要根据设计稿数值反推
- 若 QQ/飞书节点 bounds 的 y 值超过 2136 但小于 3200，是**屏内合法**的（曾误以为超出屏高导致引入错误过滤）