# 手动检查更新

参数设置底部原有 GitHubUpdateCard 只有浏览器下载按钮。当前 MainActivity、App 和更新目录
均没有自动检查、每日计时、后台任务或更新弹窗实现；仅打开下载链接不会在应用内比较版本。

新增“检查更新”在用户点击时调用 GitHub 最新正式版接口，在 IO 线程读取信息，
页面直接显示新版本、已是最新、当前版本比正式版更新、格式无法比较或请求失败。
保留下载按钮和可复制链接。接口说明：
https://docs.github.com/en/rest/releases/releases#get-the-latest-release

网络权限仅为主动检查所需。没有添加后台定时检查，也没有新增自动弹窗或下载安装行为。
数字版本按各段比较，允许一个 v/V 前缀及末尾补零；未知命名（包括预发布标记）不猜测版本顺序。
超时、HTTP 错误、限流或无公开正式版均显示错误，不显示“已是最新”。

下载按钮移除 MAIN/CATEGORY_APP_BROWSER selector，先解析通用 HTTPS 链接的默认浏览器，
再用其包名打开真实下载链接，避免系统预置浏览器或 GitHub 深链接接管。
只有可处理通用网页的浏览器才被采用；未设置可用默认浏览器时提示设置或复制链接。
2026-10-14 只读解析手机默认浏览器结果为 mark.via/.Trampoline，未启动浏览器或安装 APK。

Gradle 的 java.util.Properties 导入已移到 plugins 之前，以使用规范的 Kotlin 脚本导入位置。
用户将版本名改为 1.4.0 的未提交修改保留。应用源码曾成功编译，打包清理阶段因
app/release 目录被其他程序占用失败。之后按用户要求不再构建；最终默认浏览器修改尚未重新编译。
GitHubReleaseProbe 覆盖数字比较、相等、本地领先、未知标签、无效返回；该新增探针尚待运行。

诊断开关是 Log.isLoggable("ColgPopupPerf", Log.DEBUG)，按 Android 日志最低级别控制。
DEBUG 允许诊断采样，INFO 级别高于 DEBUG，因此阻止 DEBUG 诊断执行；它不是布尔开关。
关闭时也可恢复开始前的空属性。该设置仅控制 ColgPopupPerf 标签，不会关闭系统其他日志。
