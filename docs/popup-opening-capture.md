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


## 1.3.12：查找打开的文件夹内部阶段与结果

1.3.11 的 131 次实际采样中，`findOpenFolderView` 平均耗时 12.065 ms（CPU 11.978 ms），
占四个查找入口的 87.33%。本轮继续细分这个入口：

| 字段 | 内容 |
| --- | --- |
| folder_launcher | 解析 Launcher 对象 |
| folder_oem | 尝试系统的打开文件夹查询接口 |
| folder_tree | 搜索 View 树 |
| folder_icons | 从桌面文件夹图标关联查找 |

计时仍排除子阶段：这四项与 `findOpenFolderView` 本身的剩余时间相加，才对应上一版该入口的总时间。
不能只看本版 `findOpenFolderView` 字段变小就认定性能提高。

每条采样末尾新增 `outcomes:` 计数，按本次采样清零：
- Launcher：`found` / `missing`。
- OEM 查询按类名记录：`found`、`returned_null`、`returned_not_open`、`returned_non_view`；
  或 `class_missing`、`method_missing`、`lookup_failed`、`invoke_failed`、`validation_failed`。
- View 树、图标关联查询：`found` / `not_found`。
- 外层查询异常：`folder_search.failed`。

`returned_null` 只证明方法被成功调用并返回 null，接口是否权威代表“没有打开的文件夹”仍需核对 OEM 语义。
`returned_not_open` 表示返回了 View，但未通过现有的文件夹类型、可见性、尺寸或打开状态检查。
保留原有查询顺序和返回行为，不缓存查询结果，不提前跳过兼容路径。
只记录固定结果分类，不输出异常消息、桌面内容或截图。

设备测试通过实际反射调用验证：正常返回 null、方法不存在、类不存在、方法抛异常分别落入不同结果，
并验证下一次采样清空计数。抓真实操作时仍需先安装新版并重启桌面组件，再反复打开、关闭同一个大菜单。


## 1.3.13：成功查询为空时结束查找，缓存方法元数据

### 根因与已核实的接口

1.3.12 的 144 对采样中，三个系统接口均成功返回 null，随后每次仍执行 View 树扫描和桌面图标关联扫描。
错误的状态表达是把“成功查询后没有打开的文件夹”和“查询不可用/失败”都折叠成 null。
其中图标关联扫描平均 7.351 ms，树扫描 0.545 ms；系统接口查询平均 3.058 ms。
这些均为上一版特定菜单的实测，不是本次修复已取得的收益。

已读取手机 `/system_ext/priv-app/OplusLauncher/OplusLauncher.apk`（16.3.16 / 160030016）：
SHA-256 `78c9acfbfab9374e054da7065cec51b83c01e1d08bf95273828aad0cf5cfd5b4`。
`AbstractFloatingView.getOpenFolder(ActivityContext)` 调用 `getOpenView(context, TYPE_FOLDER)`，
后者检查 DragLayer 与 Launcher 的 RecentContainer，匹配文件夹类型且 `isOpen()` 为 true。
因此正常返回 null 可以作为当前没有打开文件夹的答案。
本机反编译证据保存于 `app/build/launcher-query-inspect/AbstractFloatingView.java`，不随源码提交 APK 或反编译文件。

### 修改

新增 Kotlin `OpenFolderQuery`，返回 `available` 与 `folder`，明确区分查询失败和有效空结果。
通过精确参数 ActivityContext、static 修饰符、Folder 返回类型与 Launcher 类型匹配确认 API，
避免以前只按方法名和参数数量挑选重载。

`ClassValue` 按 Launcher 的实际 Class 缓存 Method 及不可用的解析结果，区分不同 ClassLoader。
不持有 Launcher 实例，也不缓存文件夹状态；每次采样都重新调用系统方法。
调用阶段的异常只让本次走兼容路径，不将异常结果写入方法缓存。

正常返回 null 立即结束查询。正常返回文件夹时仍执行原来的可见性、尺寸和打开状态验证。
接口缺失、签名不兼容或调用失败时，保留 Folder / OplusFolder 的旧接口查询与树、图标关联扫描。
没有降低刷新率、改变模糊效果或加入延迟。
新类用 Kotlin，现有 Java 调用入口保留 Java，通过静态入口和属性 getter 互操作。

### 验证

`OpenFolderQueryProbe` 在设备上调用生产查询入口，验证：
- 即使兼容树中存在候选文件夹，权威 null 也不再落入扫描（旧代码会返回候选对象）。
- null → 文件夹 A → 文件夹 B → null，每次都反映当前状态。
- 系统调用异常时保留兼容查找，异常恢复后能够继续查询。
- 同名错误重载不会被选中，不兼容的 Launcher 类型不会被错误传入系统接口。
- 方法元数据复用。

另用隔离 ClassLoader 加载手机真实 Launcher APK（绕过测试类），验证生产解析器得到实际签名：
`public static Folder AbstractFloatingView.getOpenFolder(ActivityContext)`。
既有 GPU、模糊、通知、菜单、文件夹回归测试均通过；不安装测试 APK、不重启桌面。

安装 1.3.13 并重启桌面后，普通桌面无打开文件夹的采样应仅有一次系统查询，
`folder_tree`、`folder_icons` 不再出现。`folder_query_cache.resolve` 在解析发生且诊断已开启时记录。
仍需用相同菜单复测，确认耗时变化和打开文件夹内部长按时的视觉效果。


## 1.3.14：图标按 Canvas 类型绘制，复用软件转换

1.3.13 大菜单段 149 次采样的总耗时中位数为 63.469 ms，小菜单段 190 次为 18.341 ms。
文件夹查询均值分别约 0.030 / 0.022 ms，扫描已消除；`bitmap_copy` 仍约 15.080 / 3.029 ms。
这些是修复前的测量，不能当作本次修改已获得的性能收益。

### 被违反的绘制与所有权约束

文件夹预览备用绘制路径与其他图标绘制路径原先统一调用 softwareBitmap；
硬件 Canvas 原本可以直接绘制硬件位图，因此其转换没有必要。
软件 Canvas 需要转换，但同一不可变图标的转换结果应可重复使用。
softwareBitmap 也用于像素检查及动态拖动快照，其中部分调用方回收返回的临时副本；
若直接让这个函数返回共享缓存，会破坏所有权或让实时快照变旧。

现在六处图标绘制入口统一调用 bitmapForCanvas。硬件 Canvas 直接使用原位图；
软件 Canvas 使用新的 Kotlin DesktopIconBitmapCache。像素检查及实时快照继续获得独立转换。
现有 Java 文件保留 Java，新缓存使用 Kotlin，通过普通实例方法互操作。

### 缓存状态与生命周期

缓存只管理来自图标 Drawable 的硬件位图，不缓存壁纸、通知截图或动态拖动帧。
以源位图身份、generationId、density、ColorSpace、alpha 与预乘状态检查有效性。
软件位图直接返回源对象，软件图标内容更新不会被缓存遮蔽；替换图标/主题位图会使用新来源。
预算为 16 MiB 软件像素和 128 个条目，按最近使用顺序淘汰，避免微小图标无限增加条目。
源位图引用也受条目数量限制；预算不代表进程总内存上限。

缓存返回值由绘制调用方借用，调用方不得修改或 recycle。
替换、淘汰、清空只移除缓存引用，不回收旧副本，避免已有绘制引用失效；由 GC 在无引用后释放。
缓存随 Launcher 进程存活，跨次菜单打开可以复用；因条目身份与元数据检查，不需要随整个菜单缓存清空。
硬件来源回读失败时不缓存失败结果，保留原有 Drawable 绘制路径；内存不足时释放缓存引用。

日志 outcomes 增加 icon_bitmap.hardware_direct、cache_hit、copy、copy_failed、memory_pressure、oversize，
区分调用次数与真正执行的复制次数。诊断仍默认关闭。

### 验证范围

新增 DesktopIconBitmapProbe 检查硬件直接绘制、转换复用、元数据/来源更新、可变软件图标、
条目及字节预算淘汰、超大条目不入缓存、硬件/软件 Canvas 下实际备用文件夹预览像素，
以及临时副本回收不会破坏借用的缓存图片。
本轮 release 和探针均已编译成功；2026-10-14 手机重新连接后，全部设备回归测试通过，
包括新增的硬件直接绘制、软件副本复用、淘汰与所有权检查。测试未安装 APK 或重启桌面；
确认手机仍安装 1.3.13，诊断属性已恢复为空。运行输出保存在 app/build/icon-bitmap-probes-connected.log。
安装后的实际长按性能仍需复测；即使位图转换减少，离屏文件夹成品回读等其他成本仍存在。
