# 监控预览 Android 客户端

专为第三方 Docker 监控平台（H5 页面）定制的 Android WebView 容器应用，输入服务器网址即可登录预览。

## 主要功能
- **输入网址即用**：支持 `http://`（局域网明文）和 `https://`（支持自签名证书开关）。
- **地址记忆与历史切换**：自动记住最近访问过的服务器地址，下次打开直达页面，右上角悬浮条可随时点「换网址」「刷新」「横屏」。
- **视频播放优化**：开启硬件加速、媒体自动播放、WebRTC/权限自动授权、HTML5 视频全屏自动横屏。
- **防误触与常亮**：支持预览时保持屏幕常亮、双击返回键退出应用、Cookie/LocalStorage 持久化登录状态。

## 如何使用 GitHub Actions 云端打包 APK

1. 在 GitHub 上新建一个仓库（公开或私有均可）。
2. 将本项目整个目录上传（Push）到仓库的 `main` 或 `master` 分支：
   ```bash
   git init
   git add .
   git commit -m "init android app"
   git branch -M main
   git remote add origin https://github.com/你的用户名/你的仓库名.git
   git push -u origin main
   ```
3. 打开 GitHub 仓库页面的 **Actions** 标签页，等待 `Build Android APK` 工作流运行完成（约 1~2 分钟）。
4. 点进运行成功的任务，在底部 **Artifacts** 区域点击下载 **`AIVideo-Android-APK`**，解压得到 `app-debug.apk` 安装到安卓手机即可。
