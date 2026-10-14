# 桌面长按菜单的文件夹批量绘制

## 证据与边界

2026-10-14 的 1 Hz 真机日志中，三个新打开的大菜单前 12 次采样的中位耗时
分别约 52.4、48.6、47.4 ms。常见单次包含 6 次硬件快照，部分帧包含 12 次。
单纯降低静止采样率不改变开启动画中这些同步采样的成本。

`folderCompositeBitmap()` 对共享壁纸文件夹有意保持实时，不能用 `null` 位图地址
作为版本号永久复用缓存。问题在于每个文件夹分别进入 `GlassHwRasterizer.render()`，
分别建立 ImageReader/HardwareRenderer、等待 GPU、复制到 CPU 并销毁资源。

## 修改后的流程

1. `paintDesktopBehindHost()` 为现代 ColorOS 桌面长按菜单创建一次性 `PopupFolderBatch`。
2. 原有图标遍历继续执行拖动来源排除、相交判断、打开文件夹判断。符合条件的共享壁纸
   文件夹加入批次；应用、小组件、普通缓存文件夹等加入按原顺序执行的软件绘制命令。
3. 文件夹按行排列进 atlas，各自保留本地坐标、裁剪和透明间隔。所有实时玻璃、预览图标、
   文件夹名称录制到同一个硬件画布，一次 GPU 绘制、一次同步回读。
4. 软件命令按原层叠顺序执行。每个 atlas 区域裁出独立 Bitmap，继续存进原有 FOLDER_SNAPS，
   再贴到原来的目标矩形；这样系统临时移除实时玻璃时仍可使用最后一份文件夹图像。
5. 命令执行完释放 atlas。独立裁图由 FOLDER_SNAPS 持有、替换及释放。

裁图仍有 CPU 分配/拷贝成本，这是为保留原有缓存所有权和 OEM 过渡行为作出的取舍，
不是零拷贝。没有添加跨帧冻结，也没有降低分辨率。

只有共享壁纸、仍带实时玻璃的文件夹参加批量 GPU 绘制。局部位图缓存、OEM 临时移除的
玻璃、ColorOS 14、打开文件夹内菜单、拖动、SystemUI 菜单/音量/通知均保留原路径。
GPU 失败返回未完成，交给现有背景采样入口处理；不再叠加一轮逐个硬件重试。

## 观察指标

开启 `ColgPopupPerf=DEBUG` 后：

- `folder_batch.items=N`：本次批量处理的文件夹数量。
- `folder_batch.passes=1`：本次尝试的一次 atlas 硬件绘制。
- `folder_record`：每个文件夹的绘制命令录制。
- `folder_batch_split`：CPU 裁图成本。
- `hwSnaps`、`gpuWaitMs`、`readbackMs`：继续统计整次采样的全部硬件绘制。

6 个实时文件夹的硬件提交从 6 次合并为 1 次。首次应用角标快照等仍可能产生额外提交，
所以整次采样的 `hwSnaps` 不保证总为 1。实际开启动画收益必须用安装后的日志验证。

## 检查

- `:app:assembleRelease`：release 编译与打包。
- `tests/Run-HostTests.ps1`：原有 1 Hz 调度/配置回归。
- `tests/device/Run-Probes.ps1` 的 `PopupFolderBatchProbe`：真实 AGSL 的逐个绘制与 atlas
  对比，包含六种文件夹尺寸、三档缩放、透明裁剪、硬件预览图标、文字、穿插软件层、
  背景变化、裁图存活期和无文件夹路径。允许少量 GPU 视口精度差异，不能有明显图像变化。

当前实现已完成 release 编译与主机回归。真机探针首次运行在上传阶段因 USB 断开停止，
尚未取得修改后的像素对比和端到端性能数据。
