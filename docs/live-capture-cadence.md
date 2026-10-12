# 1.3.6：音量背景限帧与菜单任务调度

设置「音量条背景更新」复用 LiquidSlider，六档为 10、15、30、60、90、120 FPS，
默认 30。滑条使用 0–5 的档位索引，而不是在 10–120 之间取任意整数。
`steps=4` 表示四个中间刻度，加两个端点共六档。其他滑条默认 `steps=0`，仍连续变化。
原来的 visibilityThreshold 只控制动画精度，不承担档位吸附。

GlassConfig 保存 volume_backdrop_fps；RootController 同步并核对这个整数。
旧配置没有该键时使用 30，导入不支持的档位也使用 30。保存后重启系统组件生效。

CaptureCadence 是新增 Kotlin 类，用单调纳秒时钟记录下一次采样期限。
Java 截图代码继续保留 Java，通过普通对象方法调用它。`Long?` 表示期限可以不存在，
第一次截图和关闭后重新打开都没有历史期限。只在接受任务后推进期限，忙碌任务不会
消耗采样机会；漏过的期限跳到未来，不排队补帧。上限仍受屏幕刷新率和截图耗时限制。

音量背景 APP 截图与 DESKTOP 区域采样都受档位控制。首次没有缓存和显式强制刷新
立即执行；滑块和 OEM 动画的 postInvalidateOnAnimation 不限帧。背景仍是前台 App
或已有桌面内容来源，未改为静态壁纸。低档位意味着重复显示上一张完成的背景。

菜单调度修正：

- 移除 UI 线程 awaitFirstFrame(64ms)，首次流帧通过后续重绘读取，后台仍继续提供内容。
  首帧尚未到达时按既有无背景绘制路径显示，可能暂时看到基础玻璃。
- 区域模糊忙碌时保留最近完成的结果，先检查任务是否可接受，再裁图；避免每帧裁图
  和把未模糊 scratch 发布为背景。
- 每次关闭增加 captureEpoch。排队旧任务在开始前退出，已开始的任务完成后也不能
  写入关闭或重新打开的会话。正在执行的系统截图/GPU 运算不会被强行中断。

菜单原本已经有壁纸、图标或前台 App 的内容来源。这些修改不改变来源。
64ms 等待和忙碌时重裁图是代码里确定存在的开销；用户所见约一秒卡顿的全部原因
仍不能在未连接手机时确认，桌面长按菜单的复杂图标合成也可能需要后续性能采样。

电脑验证：CaptureCadenceProbe 在模拟 240Hz 重绘时得到各档准确的每秒采样数，覆盖
强制刷新、忙碌拒绝、长任务无补帧、关闭重置和滑条吸附。VolumeConfigProbe 对旧配置、
全部档位的读写及非法导入进行了检查。release 构建通过；手机未连接，UI 拖动、真实
视频/游戏背景、菜单开合和关闭再打开需安装后验证。

运行时钟/滑条探针（JDK 17、kotlinc）：

```powershell
kotlinc app/src/main/java/net/z841973620/colorosliquidglass/glass/CaptureCadence.kt `
  app/src/main/java/com/kyant/backdrop/catalog/components/SliderSteps.kt `
  tests/host/CaptureCadenceProbe.kt -include-runtime -d app/build/cadence-probe/probe.jar
java -jar app/build/cadence-probe/probe.jar
```

配置探针需先构建 release，再以 Android SDK android.jar 与编译后的 Java 类作为
编译/运行 classpath。Windows 的 kotlinc.bat 可用参数文件传入包含分号的 classpath。
