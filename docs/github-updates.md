# GitHub 更新入口

设置面板底部的「GitHub 更新」沿用现有白色透明玻璃，使用黑色文字：标题 18sp、正文和链接 14sp、按钮 15sp。
点击「前往 GitHub 下载」会打开浏览器并访问以下官方下载链接；链接也在面板中显示，支持选择和复制：

```text
https://github.com/rD227/ColorOSLiquidGlass/releases/latest/download/app-release.apk
```

应用不请求更新接口、不下载或缓存 APK，也不申请安装权限。下载由浏览器处理，安装由用户从浏览器或系统下载管理界面发起。

## 发布约定

保留 Gradle 现有的固定文件名，与版本号无关：

| 构建 | 文件 |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| 未签名 Release | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 配置签名的 Release | `app/build/outputs/apk/release/app-release.apk` |

以后将本地签名后的 **`app-release.apk`** 上传到 GitHub 正式 Release，并将该 Release 标记为 Latest。
如果本地签名工具输出其他名字，上传前重命名即可，文件名不影响签名。每次发布增加 `versionCode`；建议 tag 使用 `v<versionName>`。
GitHub 的固定链接会跳转到最新正式版的同名附件。如果 Latest Release 未上传 `app-release.apk`，该链接会返回 404。

签名文件继续保留在本地，不上传云端。系统覆盖安装需要与现有应用兼容的签名。
