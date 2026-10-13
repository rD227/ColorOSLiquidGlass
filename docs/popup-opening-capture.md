# 长按菜单首帧采样

## 与音量条、悬浮通知的差别

音量条和悬浮通知的 `BehindDisplayCapture` 在后台线程截取应用画面、模糊，然后发布成品。
长按菜单的 `BackdropCapture` 在 Launcher 主线程合成壁纸和桌面图标。
`DesktopIconOverlay.paintAppMenuComposite` 缓存未命中时会调用 `GlassHwRasterizer`：
创建离屏渲染器，`syncAndDraw` 等待 GPU，随后 `Bitmap.copy(ARGB_8888)` 复制为软件位图。
因此后台连续画面的流畅程度不能直接代表桌面菜单首帧的成本。
1.3.8 移除的是合成后的 CPU 模糊，上述首次图标快照成本仍然存在。

## 本次修正

此前多个 OEM 打开入口都会安装菜单；安装会立即运行一次，再 post 运行一次，每次 forceCapture。
动画结束还有一次立即采样和一次 post 采样。它们与正常 PreDraw 采样可以重叠。
违反的约束是：同一桌面菜单在一次绘制前只应完成一次当前状态的背景合成。

现在这些生命周期回调只请求更新，由下一次 PreDraw 统一采样；已安装的菜单跳过重复安装，
并删除安装和动画结束的重复 post。首帧仍在绘制前采样，没有延迟 0.5 秒，也没有降低动画帧率。
显式预览和其他组件仍可立即 forceCapture。

设备回归测试验证连续八次打开采样请求不会提前创建 backBitmap、替换 frontBitmap 或修改其像素。
GPU 模糊及零半径、源图更新、共享通知源的既有测试仍保留。

## 需要真实操作才能确认的部分

代码能证明同步等待和重复调用存在；它们各自占用户所见停顿的多少，需要实际复现测量。
未改变图标快照缓存策略，避免引入角标过期或文件夹玻璃回归。

1.3.9 增加默认关闭的 `ColgPopupPerf` 日志：

```text
adb shell setprop log.tag.ColgPopupPerf DEBUG
adb logcat -v threadtime ColgPopupPerf:D *:S
```

在打开大菜单前开始抓取，长按弹出、关闭各一次后结束抓取，再关闭日志：

```text
adb shell setprop log.tag.ColgPopupPerf INFO
```

不清空手机原有日志。只记录安装耗时和每个菜单宿主前 12 次采样：
`totalMs`、`hwSnaps`、`gpuWaitMs`、`readbackMs`、尺寸与发布状态，不记录通知内容或截图。
`gpuWaitMs` 与 `readbackMs` 是 `totalMs` 的子集，不能再次相加。
120 Hz 的单帧预算约 8.33 ms；这些日志只度量模块相关阶段，不代表完整帧耗时。
如果模块阶段很短而实际仍卡，需要进一步用系统帧时间/Perfetto 检查 OEM 动画与渲染线程。


## 分阶段诊断（2026-10-13）

上一轮真实长按操作采集到 175 次采样：中位数约 40 ms，最大 99.44 ms；
没有图标硬件快照的 21 次采样中位数仍约 24.29 ms。
这证明背景合成阻塞主线程，但不能仅凭总耗时认定 GPU 或线程调度是唯一原因。

新增 `cpuMs`（当前线程 CPU 时间）与 `offCpuMs`（总墙钟时间减 CPU 时间）。
后者包含等待、阻塞和被调度出去的时间，不能直接称为调度延迟；要进一步区分需要 Perfetto 的调度轨迹。

`stages(wallMs/cpuMs/calls)` 中，每个阶段依次记录墙钟毫秒、CPU 毫秒、调用次数：

| 字段 | 测量内容 |
| --- | --- |
| traversal | 桌面图标容器、打开的文件夹、拖动来源查找；包含这些入口内的反射 |
| wallpaper_lookup | 获取壁纸 Drawable |
| wallpaper | 软件 Canvas 上的壁纸绘制、坐标与边界调整 |
| shared_wallpaper | 文件夹共享 GPU 壁纸获取、缓存未命中时的生成 |
| app_composite / folder_composite / widget_composite | 对应桌面元素的合成 |
| desktop_chrome | 桌面附属元素（例如页面指示器）的绘制 |
| software_draw | View / Drawable 软件快照 |
| bitmap_alloc | 本地采样及软件快照的显式 createBitmap（包括初始化像素） |
| bitmap_copy | 图标硬件位图转软件位图 |
| hw_setup_record_cleanup | 离屏渲染器建立、命令录制、清理 |
| gpu_wait | 图标离屏渲染 syncAndDraw |
| gpu_readback | 硬件快照复制到软件位图（含复制所需分配） |
| other | 未细分工作和计时开销 |

这些阶段使用**排除子阶段后的时间**，避免重复计算。例如文件夹合成中的 GPU 等待只算入
`gpu_wait`，不会再次计入 `folder_composite`。递归层级查找按一次外层操作统计。
旧字段 `gpuWaitMs` / `readbackMs` 为兼容保留，与阶段明细重叠，不要再相加。
未记录的系统内部分配、其他线程或驱动内部工作无法靠这些计时直接拆开。

仍然默认关闭，开启方式不变，每个菜单宿主最多 12 次。关闭时不创建阶段对象；
开启时只在本次主线程采样中汇总，最后统一打印，不在遍历每个 View 时输出日志。
记录时间的精度与测量开销会带来小误差，应比较多次、相同菜单大小、相同桌面内容的分布。
诊断提交只加计时，后续修复单独提交，方便按相同流程比较。


## 修复：去掉文件夹硬件合成之前的无用软件采样

违反的约束：只需要共享 GPU 壁纸的绘制路径，不应为了查询缓存状态生成软件壁纸位图。

`folderCompositeBitmap` 原来先调用 `BackdropCapture.snapshotOf(bgView)`，再检查文件夹缓存。
对共享壁纸宿主而言，`snapshotOf` 不只是读取已有结果，而是同步执行一次本地软件采样。
随后 Cos16 菜单调用 `hwFolderComposite`，实际直接绘制 `GlassDrawable`，读取的是共享 GPU 壁纸；
刚生成的软件位图只被当作缓存身份，没有参与这次硬件合成。

现在只读查询宿主的共享壁纸路由，在这个明确的硬件路径上跳过软件快照。
Cos14 软件合成和拖动路径保持原有行为。缓存显式标记 `sharedLivePlate`：
共享纹理没有本地 `plateSource`，因此不能把两个 null 相等误判为内容没变。
文件夹预览、材质、位置仍实时绘制；OEM 临时移除玻璃时仍可保留上一张成品。
没有加入固定半秒延迟、降低采样频率或跨次打开缓存整个桌面。

新增设备回归测试在旧实现上触发“Hardware folder composite unnecessarily materialized a software wallpaper”。
修复后验证：硬件合成不产生本地 front/back 位图；更换预览颜色后下一张合成图更新；
显式软件消费者仍可请求快照；卸载宿主玻璃后过渡缓存保留。
分阶段计时也在设备上验证了开启、嵌套、递归合并与结束后清理。

这些测试证明路径与输出约束，不能代替用户桌面上的性能实测。
下一轮应在相同大菜单、相同桌面上重复长按，比较 totalMs 分布及具体阶段。
若要严格 A/B，可使用同轮保存的 diagnostics-only APK（仅计时，仍为 1.3.9）和修复版；
两者诊断代码相同。先测旧版再测新版可避免 versionCode 降级限制。


## 1.3.11：拆分层级查找入口

1.3.10 实际长按记录中，`traversal` 平均约 15.24 ms，其中线程 CPU 约 15.01 ms。
原来的合并计时无法判断具体哪个入口最慢。1.3.11 只拆分日志标签，绘制、采样与缓存策略不变：

| 新日志字段 | 对应工作 |
| --- | --- |
| collectDragSources | 遍历 View 树收集正在拖动的图标来源 |
| collectDragControllerSources | 查找 Launcher / DragController，读取拖动来源 |
| findOpenFolderView | 查找打开的文件夹，包括 OEM 方法、View 树和图标关联的查询 |
| iconContainersUnderRegion | 查找玻璃覆盖区域内的桌面图标容器与底栏容器 |

四项均保持 `wallMs/cpuMs/calls` 格式；其排除子阶段后的耗时之和对应之前的 `traversal`。
`collectDragSources` 的递归调用继续合并到最外层：调用次数表示外层查找次数，不是访问的 View 数量。
仍只记录每个菜单宿主前 12 次采样，默认关闭，不逐个 View 输出日志。

安装并重启桌面组件后，用相同的大菜单反复打开/关闭约 30 秒，再比较各入口的墙钟和 CPU 时间。
若某个入口成为主要热点，再针对它细分反射查找或遍历；此版本不预先改动这些行为，以保持测量可比。
