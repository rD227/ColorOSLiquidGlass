# 分组标题与横屏通知

2026-10-13 从测试手机的 SystemUI APK 检查了以下实际绘制路径：

* 展开分组标题由 `NotificationChildrenContainerExtImp.getHeaderBgDrawable(View, boolean,
  ExpandableNotificationRow)` 返回独立背景，原来的 `NotificationBackgroundView.onDraw` hook 不会覆盖它。
  新 hook 返回 `NotificationPlateDrawable`，顶角取所属 row 的 `getTopCornerRadius()`，底角为零。
  系统仍管理标题文字、箭头、点击、父级裁剪与展开动画。
* 横屏顶部通知由 `FullScreenBannerContainer.onViewAttachedToWindow(View)` 给
  `mFullScreenBanner` 安装背景。新 hook 在这里安装玻璃，并采用系统资源
  `fullscreen_banner_corner_radius`。它使用实时应用画面，帧率与音量条共享。
  `FullScreenBackgroundView` 是该系列的另一种图标/动画底板，并不是截图中普通横幅的主背景。

`NotificationPlateDrawable` 是组合：它持有 `GlassDrawable` 和 `NotificationGlassSource`，
不继承最终类 `GlassDrawable`。它自己继承 Android 的 `Drawable`，以满足 OEM 背景接口。
更换或重复绑定背景时释放旧采样；离开窗口后释放采样，重新进入时重新创建材质。

两个 hook 独立解析接口；不兼容的 ROM 记录日志并保留系统原样式。
`OemNotificationContractProbe.kt` 在设备上加载真实 SystemUI APK 验证方法和字段契约，
不安装模块、不重启 SystemUI。命令参数为 APK 路径，例如：

```text
app_process /system/bin net.z841973620.colorosliquidglass.glass.OemNotificationContractProbeKt /system_ext/priv-app/SystemUI/SystemUI.apk
```

需先按 `Run-Probes.ps1` 设置测试 classpath。接口验证不等同于真实通知交互验收；
最终仍要检查展开/折叠、横屏横幅出现/消失和低高斯模糊设置下的视觉表现。
