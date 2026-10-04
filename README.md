# Simple Player Android

Simple Player 的 Android Client。

使用 Jetpack Compose 建立介面，並透過 Simple Player Server 的 Mobile API 取得音樂資料與播放內容。

## 功能

* Android 原生音樂播放器
* Jetpack Compose UI
* AndroidX Media3 播放引擎
* MediaSession / 系統媒體控制
* 播放清單
* 封面顯示
* 歌詞顯示
* 本地 Metadata Cache
* 與 Simple Player Web Server 整合

## Requirements

* Android Studio
* JDK 17
* Android SDK 35

本專案可以直接使用 Android Studio 開啟。

不需要自行建立新的 Android Project。

## 開啟專案

Clone Repository 後，在 Android Studio 中選擇：

```text
Open
```

然後開啟本專案的根目錄。

等待 Gradle Sync 完成後即可進行編譯與執行。

本專案包含 Gradle Wrapper，因此不需要另外安裝 Gradle。

## Build

Debug：

```powershell
.\gradlew.bat :app:assembleDebug
```

Release：

```powershell
.\gradlew.bat :app:assembleRelease
```

APK 會輸出至：

```text
app/build/outputs/apk/debug/app-debug.apk
```

或：

```text
app/build/outputs/apk/release/app-release.apk
```

本專案不提供預先編譯好的 APK。

使用者可以直接在 Android Studio 中依自己的環境與需求進行 Build。

## Package Name

目前預設 Package Name / Application ID：

```text
com.akira.simpleplayer
```

這是作者目前使用的 Package Name。

如果你要將本專案作為自己的 Fork 或衍生專案，可以自行修改成自己的 Package Name。

需要修改的部分包含：

```kotlin
android {
    namespace = "your.package.name"

    defaultConfig {
        applicationId = "your.package.name"
    }
}
```

以及 Kotlin source 中的：

```kotlin
package your.package.name
```

與對應的 source directory。

熟悉 Android Studio、Gradle 與 Kotlin 的使用者可以自行修改。

## Server

本 App 需要搭配 Simple Player Web Server 使用。

[SimplePlayer-Web-Server](https://github.com/Akiraoo/SimplePlayer-Web-Server)

開源版本的 Simple Player Server 預設使用：

| 功能         | Port |
| ---------- | ---: |
| Web Server | 8787 |
| Mobile API | 8788 |

Android Client 應連接 Mobile API，例如：

```text
http://192.168.0.100:8788
```

其中 `192.168.0.100` 必須替換成實際執行 Simple Player Server 的電腦 LAN IP。

### 注意

Android 裝置中的：

```text
localhost
127.0.0.1
```

代表的是**Android 裝置本身**，不是你的電腦。

因此如果 Server 執行於電腦上，不能直接將：

```text
http://localhost:8788
```

填入 Android Client。

## 本地 Metadata Cache

App 支援將 Metadata 快取於 Android 裝置本地。

本地 Metadata Cache 的用途包括：

* 減少重複向 Server 請求 Metadata
* 加快重新開啟 App 時的資料顯示
* 減少不必要的網路請求

這個 Cache 與 Server 端的 Metadata Cache 是分開的。

也就是：

```text
Android Client
    │
    ├── Local Metadata Cache
    │
    ▼
Simple Player Mobile API
    │
    ├── Server Metadata Cache
    └── Music Files
```

本地 Metadata Cache 只保存相關 Metadata，不會將整個音樂庫下載到手機。

清除 App Cache 或 App 資料也不會影響 Server 上的原始音樂檔案。

## 技術

| Component         |    Version |
| ----------------- | ---------: |
| Compile SDK       |         35 |
| Target SDK        |         35 |
| Minimum SDK       |         26 |
| Java              |         17 |
| Kotlin JVM Target |         17 |
| Compose BOM       | 2025.01.00 |
| AndroidX Media3   |      1.5.1 |
| Coil              |      2.7.0 |
| Kotlin Coroutines |     1.10.1 |

## Signing

本專案不包含作者個人的 Android signing key。

正式發布自己的 APK 時，請使用自己的 signing key。

請不要將以下檔案提交到公開 Repository：

```text
*.jks
*.keystore
keystore.properties
```

## License

本專案採用 **Apache License 2.0** 授權。

完整授權條款請參閱 Repository 根目錄的 `LICENSE`。

SPDX-License-Identifier：

```text
Apache-2.0
```

## 相關專案

Simple Player Web Server：

[SimplePlayer-Web-Server](https://github.com/Akiraoo/SimplePlayer-Web-Server)
