# 通知中心背景采样

通知背景与几何形状分别管理：`NotificationGlassSource` 选择背景，普通卡片仍由
`NotificationCardGlass` 读取 OEM 的高度、圆角、裁剪和透明度。

背景按以下顺序选择：锁屏使用锁屏壁纸；顶部悬浮通知使用音量条同档位的实时应用采样；
普通通知根据“通知卡片采样应用背景”开关选择共享应用背景或原有共享壁纸。
默认关闭应用采样。新增的分档滑条为 1、2、5、10、15、30 FPS，默认 5 FPS。

`SharedNotificationBackdrop` 按窗口管理采样源。多张卡片和分组标题共用同一张全窗口纹理，
由 `BehindDisplayCapture` 排除 SystemUI 自身窗口后采样并在后台模糊。
玻璃使用每一帧的本地坐标到根视图的矩阵来取样，所以采样限速不限制滑动动画。
卡片不可见或所有者退出时停止采样；最后一个使用者退出时移除监听器和定时刷新。
锁屏背景、桌面文件夹及音量条原有策略不受此开关控制。

配置增加 `notification_app_backdrop` 和 `notification_backdrop_fps`，保存后需重启系统组件。
配置往返和旧配置默认值由 `tests/host/VolumeConfigProbe.kt` 验证。

新文件使用 Kotlin：`object` 表示单例；`WeakHashMap` 不通过键保留已经退出的 View；
`@JvmStatic` 让现有 Java 调用者可直接调用静态方法。已有 Java 绘制器保留原语言。
