# 长按菜单：移除绘制线程上的 CPU 模糊

此前菜单每次更新背景后，`GlassDrawable.blurredBackdrop()` 都会运行
`BackdropBlur.blurCopy()`：读取全尺寸像素、面积降采样，再进行 CPU 模糊 
这些工作发生在绘制调用中，大窗口会放大像素遍历和临时数组的成本 

现在仅对桌面长按菜单使用 `PopupRenderEffect`：

```text
当前壁纸和图标合成图 → GPU BlurEffect → 原有液态玻璃 RuntimeShader → 窗口
```

复用一个 RenderNode，将模糊与折射一起交给窗口的 RenderThread 
不会创建 CPU 模糊结果，也不启动额外 HardwareRenderer 并同步等待 GPU 回读 
背景仍然逐帧更新，不延迟前 0.5 秒，不跨次打开缓存桌面，不降低动画刷新率 
模糊为零时省略 BlurEffect 

依据：[Android RenderEffect 文档](https://developer.android.com/reference/android/graphics/RenderEffect) 
`createChainEffect(outer, inner)` 执行 outer(inner(source))，因此先模糊背景、再折射，
不会把最终玻璃边缘及菜单文字一起模糊 

设备测试 `NotificationAndPopupProbe.kt` 实际经过 GlassDrawable 的菜单分支：
验证黑白边界被平滑、零半径保持锐利、源图变化即时可见、CPU 模糊缓存为空 
现有壁纸、通知对比度、采样抗混叠、悬浮通知路由测试均通过 

仍存在桌面图标/壁纸合成本身的成本 此改动移除了已确认的同步 CPU 热点，
没有测出真实 Launcher 整段动画的帧率，不能据此承诺达到 120 FPS 
