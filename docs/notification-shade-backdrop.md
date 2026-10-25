# 通知中心背景采样

通知背景与几何形状分别管理：`NotificationGlassSource` 选择背景，普通卡片仍由
`NotificationCardGlass` 读取 OEM 的高度、圆角、裁剪和透明度。

背景按以下顺序选择：锁屏使用锁屏壁纸；顶部悬浮通知使用音量条同档位的实时应用采样；
普通通知根据“通知卡片采样应用背景”开关选择共享应用背景或原有共享壁纸。
默认关闭应用采样。分档滑条为 0、1、2、5、10、15、30 Hz，默认 0 Hz。
0 Hz 在每次展开、卡片首次可见时提交一次后台采样，之后保持该纹理，不安排定时刷新。
卡片全部不可见后释放纹理并重置；下次展开重新采样。采样失败时不会在绘制回调中反复重试。
已有配置中显式保存的非零档位继续保留；缺少频率配置时使用新的 0 Hz 默认值。

`SharedNotificationBackdrop` 按窗口管理采样源。多张卡片和分组标题共用同一张全窗口纹理，
由 `BehindDisplayCapture` 排除 SystemUI 自身窗口后采样并在后台模糊。
内部 `ShadeSession` 按 `requestCapture`、`publishNewFrame`、`scheduleNextRefresh` 三步处理
每次绘制；隐藏时结束本次采样会话，最后一个使用者退出时解除所有监听。
玻璃使用每一帧的本地坐标到根视图的矩阵来取样，所以采样限速不限制滑动动画。
卡片不可见或所有者退出时停止采样；最后一个使用者退出时移除监听器和定时刷新。
锁屏背景、桌面文件夹及音量条原有策略不受此开关控制。

配置增加 `notification_app_backdrop` 和 `notification_backdrop_fps`，保存后需重启系统组件。
配置往返和旧配置默认值由 `tests/host/VolumeConfigProbe.kt` 验证。

新文件使用 Kotlin：`object` 表示单例；`WeakHashMap` 不通过键保留已经退出的 View；
`@JvmStatic` 让现有 Java 调用者可直接调用静态方法。已有 Java 绘制器保留原语言。
