# S-master Android 应用

## 功能特性

- 📱 屏幕监控与聊天内容识别
- 🤖 自动分析并给出回复建议
- 💬 支持 Soul、微信等聊天应用
- 🛡️ 被动模式，不自动发送消息，降低封号风险

## 构建指南

### 方法一：Android Studio（推荐）

1. 下载并安装 [Android Studio](https://developer.android.com/studio)
2. 安装 JDK 17+（Android Studio 通常自带）
3. 打开 Android Studio，选择 "Open an existing project"
4. 选择 `s_master-android` 目录
5. 等待 Gradle 同步完成
6. 点击 **Build > Build Bundle(s) / APK(s) > Build APK(s)**
7. APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

### 方法二：命令行构建

```bash
# 确保已安装 JDK 17+ 和 Android SDK
cd s_master-android
./gradlew assembleDebug

# APK 输出位置
# app/build/outputs/apk/debug/app-debug.apk
```

### 方法三：GitHub Actions 自动构建（无需本地环境）

1. 将项目上传到 GitHub 仓库
2. 项目已配置 `.github/workflows/android.yml`
3. GitHub Actions 会自动构建 APK
4. 在 Actions 页面下载构建产物

## 安装 APK

1. 将 APK 文件传输到手机
2. 在手机上打开 APK 文件
3. 如果提示"安装被阻止"，进入 **设置 > 安全 > 允许未知来源应用**
4. 安装后打开应用

## 权限说明

应用需要以下权限：

- **悬浮窗权限** - 显示回复建议悬浮窗
- **屏幕录制权限** - 读取聊天内容（MediaProjection）
- **存储权限** - 保存配置

这些权限仅用于：
- 读取屏幕上的文字
- 显示悬浮窗提示
- 不会收集或上传任何数据

## 使用步骤

1. 打开应用，点击 **开始监控**
2. 授予必要的权限
3. 打开 Soul 或其他聊天应用
4. 当检测到聊天内容时，悬浮窗会显示回复建议
5. 点击建议可复制到剪贴板
6. 粘贴到聊天输入框发送

## 风险提示

- 应用仅提供建议，不自动发送消息
- 被动模式设计，避免被平台检测
- 请合理使用，不要过度依赖

---

## 致谢

本项目基于 [tomwong001/qingsheng](https://github.com/tomwong001/qingsheng) 项目进行修改和优化。感谢原作者的开源贡献！
