# 手动检查更新与玻璃弹窗

参考用户提供的 shizuku-ftp/primitiveFTPd/src/org/primftpd/ui/AboutScreen.kt：
参数设置底部仅保留“检查更新”。点击后按钮显示“正在检查…”，后台读取 GitHub 最新正式版。
只有远端版本更高才展示玻璃更新弹窗；已是最新、本地领先、无法比较或请求失败用 Toast 提示。
检查期间忽略重复点击。没有添加每日计时、启动自动检查、后台任务或自动下载安装。

版本解析返回 ReleaseStatus（标签、比较结果、提示），界面根据比较结果决定是否弹窗，
不通过匹配提示文字判断状态。数字按段比较，支持 v/V 前缀及末尾补零；未知格式不猜测。
GitHub 接口说明：https://docs.github.com/en/rest/releases/releases#get-the-latest-release

## 弹窗

与设置项共用 drawBackdrop：vibrancy、4 dp 模糊、16/32 dp 折射、Plain 高光、
32 dp 圆角及 50% 白色表面。按钮采样弹窗自己的导出背景，支持用户更换的壁纸。
BackdropDemoScaffold 增加默认空 overlay，在所有设置控件及底部选图按钮之后绘制，
避免弹窗落在滚动面板内部或让选图按钮浮在遮罩上。它与背景同窗口，便于共享玻璃采样层。
弹窗提供下载及“稍后再说”；外侧点击和返回键均可关闭。内容可滚动，适配较小屏幕。
成功打开浏览器后关闭弹窗。

## 默认浏览器与编译修复

移除非公开 SDK 字段 ResolveInfo.handleAllWebDataURI。
仅使用公开的 resolveActivity / queryIntentActivities：通用 HTTPS 链接的默认处理器必须
也是查询到的候选处理器，才能用它的包名打开真实下载链接；系统选择器不算已选浏览器。
不设置 MAIN/CATEGORY_APP_BROWSER selector，也不指定系统预置浏览器。
没有默认浏览器或打开失败时提示用户设置/复制链接；不自动改写用户默认项。

## 验证

2026-10-14 assembleRelease 已通过，生成 1.4.0 签名 APK，保留用户现有版本名修改。
GitHubReleaseProbe 覆盖数字比较、相等、本地领先、未知格式、无效返回，以及弹窗的结构化判断。
设备实际解析下载 Intent 到 mark.via，未启动浏览器；实网检查返回正式版 v1.3.5，
当前本地版本 1.4.0，因此此时只提示本地版本更新，不应显示发现新版的弹窗。
已有设备回归测试继续保留。界面的最终外观尚需用户安装后确认；测试未替换已安装模块。

日志：app/build/update-dialog-build.log、update-dialog-probes.log、update-dialog-live.log。

## 诊断开关

Log.isLoggable("ColgPopupPerf", Log.DEBUG) 按最低日志级别控制。
DEBUG 开启，INFO 阈值高于 DEBUG，因此关闭这套 DEBUG 诊断；也可恢复开始前的空属性。
它只控制该标签，不会关闭系统其他日志。


## 中英文提示（2026-10-14）

更新相关的比较结果、网络/服务错误、按钮、弹窗标题及浏览器错误统一使用 Android 字符串资源。
values/strings.xml 为英文默认值，values-zh/strings.xml 为中文；跟随系统提供的应用资源语言，
非中文语言暂时回退英文。没有手动语言开关或硬编码 Locale.getDefault 分支。
ReleaseStatus 保存版本数据和比较结果，在展示时根据当前 Resources 格式化，避免把语言锁定在网络请求时。
网络服务异常保存资源标识及 HTTP 状态；普通网络异常显示本地化提示，不直接显示平台异常消息。

assembleRelease、既有设备回归测试以及资源测试均通过。资源测试直接读取此次 APK，
分别使用 zh-CN、en-US、de-DE 配置验证四类比较结果、版本占位符和网络错误的中文/英文回退。
日志：app/build/update-locales-build.log、update-locales-probes.log、update-locales-resources.log。
