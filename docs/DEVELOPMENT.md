# EasyPlayer-RTSP Android 开发文档

本文档面向二次开发与集成 EasyPlayer RTSP 播放能力的开发者，涵盖工程结构、编译环境、核心 API、集成方式与调试要点。

---

## 1. 项目概述

EasyPlayer-RTSP Android 是由 [EasyDarwin](https://www.easydarwin.org) 团队维护的 RTSP 流媒体播放器，适用于安防监控等低延迟直播场景。

| 类别 | 支持项 |
|------|--------|
| 视频编码 | H.264、H.265、MPEG4、MJPEG |
| 音频编码 | G711A、G711U、G726、AAC |
| 传输协议 | RTSP over TCP / UDP |
| 解码方式 | MediaCodec 硬解为主，部分场景软解 |
| CPU 架构 | armeabi-v7a、arm64-v8a、x86、x86_64 |

主要能力：超低延迟播放、多窗口多实例、TCP/UDP 切换、Buffer 配置、静音、延时追帧、快照、录像、自定义显示布局。

---

## 2. 工程结构

```
EasyPlayer-RTSP-Android/
├── EasyPlayer/          # 完整 Demo 应用（列表、播放、多路、设置、扫码等）
├── simpleplayer/        # 最简集成示例（单 Activity + TextureView）
├── library/             # 核心播放 SDK（AAR 形态可单独依赖）
├── docs/                # 文档
├── release/             # 发布 APK（可选）
├── jks/                 # 签名密钥（本地，勿提交敏感信息）
├── build.gradle         # 根构建脚本
└── settings.gradle      # 模块：EasyPlayer、library、simpleplayer
```

### 2.1 模块职责

| 模块 | 类型 | 说明 |
|------|------|------|
| `library` | Android Library | 播放核心：`EasyPlayerClient`、`Client`（JNI）、编解码、录像封装 |
| `EasyPlayer` | Application | 产品级 Demo，含 SQLite 流地址管理、多路播放、设置、更新等 |
| `simpleplayer` | Application | 最小示例，演示 30 行内完成 RTSP 播放 |

### 2.2 library 包结构

```
org.easydarwin
├── video/
│   ├── EasyPlayerClient.java   # 对外主 API（播放、录像、事件）
│   ├── Client.java             # JNI 封装，加载 libEasyRTSPClient.so
│   ├── VideoCodec.java         # 软解视频（H264/H265）
│   ├── EasyMuxer.java          # MediaCodec 录像封装
│   └── EasyMuxer2.java         # FFmpeg 录像封装
├── audio/
│   ├── AudioCodec.java         # G711/G726/AAC 解码
│   └── EasyAACMuxer.java
├── player/
│   └── EasyPlayer.java         # 高层封装（工厂模式，可选）
├── sw/
│   ├── JNIUtil.java            # YUV 旋转/格式转换
│   └── TxtOverlay.java
└── util/
    ├── CodecSpecificDataUtil.java
    └── TextureLifecycler.java
```

### 2.3 Native 库

位于 `library/src/main/jniLibs/`，按 ABI 分目录：-

| 库名 | 作用 |
|------|------|
| `libEasyRTSPClient.so` | RTSP 拉流、解复用 |
| `libVideoCodecer.so` | 视频软解、录像编码 |
| `libAudioCodecer.so` | 音频解码 |
| `libproffmpeg.so` | FFmpeg 依赖 |
| `libyuv_android.so` | YUV 处理 |
| `libTxtOverlay.so` | 文字叠加（可选） |

支持 ABI：`armeabi-v7a`、`arm64-v8a`、`x86`（EasyPlayer 模块还声明了 `armeabi`、`x86_64`）。

---

## 3. 开发环境

### 3.1 推荐配置

| 工具 | 版本（工程当前） |
|------|------------------|
| Android Studio | Arctic Fox 及以上 |
| Gradle | 7.0.2（wrapper） |
| Android Gradle Plugin | 7.0.4 |
| compileSdkVersion | 31（EasyPlayer / library） |
| minSdkVersion | 21（EasyPlayer / library） |
| Support Library | 26.1.0 |

### 3.2 编译命令

```bash
# 编译完整 Demo
./gradlew :EasyPlayer:assembleDebug

# 编译最简示例
./gradlew :simpleplayer:assembleDebug

# 编译 library AAR
./gradlew :library:assembleRelease
```

Release APK 命名规则（EasyPlayer 模块）：

```
EasyPlayerRTSP-{versionCode}-{versionName}.apk
# 示例：EasyPlayerRTSP-14260428-1.4.26.0428.apk
```

### 3.3 依赖自有工程

在宿主 `settings.gradle` 中：

```gradle
include ':library'
project(':library').projectDir = new File('../EasyPlayer-RTSP-Android/library')
```

在 app `build.gradle` 中：

```gradle
dependencies {
    implementation project(':library')
}
```

或直接引用构建产物：`library/build/outputs/aar/library-release.aar`。

---

## 4. 架构与数据流

```
┌─────────────────────────────────────────────────────────────┐
│  UI 层 (Activity / Fragment / TextureView)                 │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  EasyPlayerClient                                            │
│  - 硬解：MediaCodec + Surface                                │
│  - 软解：VideoCodec (JNI)                                    │
│  - 音频：AudioCodec + AudioTrack                             │
│  - 录像：EasyMuxer / EasyMuxer2                              │
│  - 事件：ResultReceiver 回调                                  │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  Client (JNI) → libEasyRTSPClient.so                         │
│  RTSP 连接 → 音视频帧回调 → FrameInfo 队列                    │
└─────────────────────────────────────────────────────────────┘
```

**播放线程模型：**

1. `Client` 在 Native 层拉流，通过 `SourceCallBack` 回调 Java。
2. `EasyPlayerClient` 将帧放入优先级队列，视频线程喂给 `MediaCodec`，音频线程喂给 `AudioCodec` 再写入 `AudioTrack`。
3. 解码后的画面渲染到 `Surface` / `TextureView`。

---

## 5. 核心 API（EasyPlayerClient）

### 5.1 创建实例

```java
// 方式一：Surface + 事件回调
EasyPlayerClient client = new EasyPlayerClient(
    context,
    surface,
    resultReceiver,      // 可为 null
    i420Callback,        // 可为 null，需要 YUV 数据时传入
    seiCallback          // 可为 null，需要 SEI 时传入
);

// 方式二：TextureView（自动绑定 Lifecycle 暂停/恢复/销毁）
EasyPlayerClient client = new EasyPlayerClient(
    context,
    textureView,
    resultReceiver,
    null,
    null
);
```

### 5.2 开始 / 停止播放

```java
// 简易播放（默认 TCP）
client.play("rtsp://192.168.1.100:554/stream");

// 完整参数
int ret = client.start(
    url,                                          // RTSP 地址
    Client.TRANSTYPE_TCP,                         // TCP=1, UDP=2
    sendOption,                                   // RTSP Option 发送策略
    Client.EASY_SDK_VIDEO_FRAME_FLAG
        | Client.EASY_SDK_AUDIO_FRAME_FLAG,       // 订阅帧类型
    "", "",                                       // RTSP 用户名/密码
    null                                          // 可选：自动录像路径
);

client.pause();   // 暂停拉流（延迟关闭）
client.resume();  // 恢复
client.stop();    // 完全停止，释放资源
```

### 5.3 音频与录像

```java
client.setAudioEnable(false);  // 静音
client.setAudioEnable(true);   // 取消静音

client.startRecord("/sdcard/record.mp4");
client.stopRecord();
boolean recording = client.isRecording();
```

### 5.4 编解码常量

**视频：**

| 常量 | 值 | 说明 |
|------|-----|------|
| `EASY_SDK_VIDEO_CODEC_H264` | 0x1C | H.264 |
| `EASY_SDK_VIDEO_CODEC_H265` | 0xAE | H.265 |
| `EASY_SDK_VIDEO_CODEC_MJPEG` | 0x08 | MJPEG |
| `EASY_SDK_VIDEO_CODEC_MPEG4` | 0x0D | MPEG4 |

**音频：**

| 常量 | 值 | 说明 |
|------|-----|------|
| `EASY_SDK_AUDIO_CODEC_AAC` | 0x15002 | AAC |
| `EASY_SDK_AUDIO_CODEC_G711A` | 0x10007 | G711 A-law |
| `EASY_SDK_AUDIO_CODEC_G711U` | 0x10006 | G711 μ-law |
| `EASY_SDK_AUDIO_CODEC_G726` | 0x1100B | G726 |

### 5.5 ResultReceiver 事件码

在 `ResultReceiver.onReceiveResult(int resultCode, Bundle data)` 中处理：

| resultCode | 含义 |
|------------|------|
| `RESULT_FIRST_FRAME_TTFF` (10) | 首帧已显示；`KEY_TTFF_MS`：自 `start()`/`play()` 起的毫秒耗时；`KEY_VIDEO_DECODE_TYPE`：0 软解 / 1 硬解 |
| `RESULT_VIDEO_DISPLAYED` (1) | **已废弃**，请改用 `RESULT_FIRST_FRAME_TTFF` |
| `RESULT_VIDEO_SIZE` (2) | 分辨率已知；`EXTRA_VIDEO_WIDTH` / `EXTRA_VIDEO_HEIGHT` |
| `RESULT_TIMEOUT` (3) | 试播超时 |
| `RESULT_EVENT` (4) | 连接事件；`errorcode`、`state`、`event-msg` |
| `RESULT_UNSUPPORTED_VIDEO` (5) | 不支持的视频编码 |
| `RESULT_UNSUPPORTED_AUDIO` (6) | 不支持的音频编码 |
| `RESULT_RECORD_BEGIN` (7) | 开始录像 |
| `RESULT_RECORD_END` (8) | 结束录像 |
| `RESULT_FRAME_RECVED` (9) | 收到第一帧数据 |

### 5.6 可选回调

```java
// YUV I420 数据（用于分析、自定义渲染）
public interface I420DataCallback {
    void onI420Data(ByteBuffer buffer);
}

// H.264 SEI 数据
public interface SEIDataCallback {
    void onSEIData(byte[] sei);
}
```

---

## 6. 快速集成（simpleplayer 示例）

参考 `simpleplayer/src/main/java/.../MainActivity.java`：

```java
TextureView textureView = findViewById(R.id.texture_view);

EasyPlayerClient client = new EasyPlayerClient(
    this,
    textureView,
    null,   // ResultReceiver
    null,   // I420DataCallback
    null    // SEIDataCallback
);

client.play("rtsp://your-server:554/live");

// Activity onDestroy 中
// client.stop();
```

**注意：** 使用 `TextureView` 构造器时，若 `context` 实现 `LifecycleOwner`，会自动在 `ON_PAUSE`/`ON_RESUME`/`ON_DESTROY` 时暂停、恢复、停止播放。

---

## 7. 完整 Demo（EasyPlayer）模块说明

### 7.1 主要 Activity

| Activity | 功能 |
|----------|------|
| `PlayListActivity` | 启动页，流地址列表（SQLite） |
| `PlayActivity` | 单路播放，控制栏（静音、录像、抓拍） |
| `MultiPlayActivity` | 多路同屏 |
| `SettingsActivity` | 全局设置（UDP 模式等） |
| `ScanQRActivity` | 扫码添加 RTSP 地址 |
| `MediaFilesActivity` | 本地录像/快照浏览 |
| `YUVExportActivity` | YUV 导出调试（需 `YUV_EXPORT=true`） |

### 7.2 播放 Fragment

`PlayFragment` 是播放 UI 的核心封装：

- 使用 `TextureView` + `PhotoViewAttacher` 支持缩放/拖拽。
- 通过 `PlayFragment.newInstance(url, transportMode, sendOption, rr)` 创建。
- `transportMode`：`VideoSource.TRANSPORT_MODE_TCP`(1) 或 `TRANSPORT_MODE_UDP`(2)。
- 内部创建 `EasyPlayerClient` 并调用 `start()`。

### 7.3 数据存储

`VideoSource` 表字段：

- `url`：RTSP 地址
- `name`：显示名称
- `transport_mode`：1=TCP，2=UDP
- `send_option`：是否发送 RTSP 活性包

数据库由 `EasyDBHelper` 管理。

---

## 8. 配置与偏好设置

通过 `SharedPreferences`（默认 `PreferenceManager`）：

| Key | 说明 | 默认 |
|-----|------|------|
| `waiting_i_frame` | 是否等待 I 帧再开始解码 | `true` |
| UDP 模式 | `SPUtil.getUDPMode()` / `setUDPMode()` | 设置页开关 |

`library` 模块 BuildConfig：

- `MEDIA_DEBUG`：为 `true` 时可将原始帧 dump 到 `/sdcard/media_degbu.data`（需存储权限）。

`EasyPlayer` 模块 BuildConfig：

- `YUV_EXPORT`：是否编译 YUV 导出功能。

---

## 9. 权限清单

Demo 应用（`EasyPlayer/AndroidManifest.xml`）声明：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />  <!-- 扫码 -->
```

集成时至少需 `INTERNET`；录像/抓拍需存储权限（Android 10+ 注意分区存储适配）。

---

## 10. 多路播放

`MultiPlayActivity` 在多个 `FrameLayout` 容器中各添加一个 `PlayFragment` 实例。每个实例拥有独立的 `EasyPlayerClient`，互不影响。

要点：

- 每个 `PlayFragment` 绑定独立 `TextureView`。
- 注意设备硬解码器数量上限，多路 H265 可能触发 `RESULT_UNSUPPORTED_VIDEO`。
- 建议在弱网环境优先使用 TCP（`TRANSTYPE_TCP`）。

---

## 11. 调试建议

### 11.1 日志 Tag

| Tag | 来源 |
|-----|------|
| `EasyPlayerClient` | 播放主逻辑 |
| `Client` | JNI 拉流 |
| `PlayFragment` | UI 层 |

### 11.2 常见问题

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 初始化失败，KEY 不合法 | Native `init` 失败 | 检查 `jniLibs` 是否完整、ABI 是否匹配 |
| 试播时间到 | 网络不通或 URL 错误 | 检查 RTSP 地址、防火墙、鉴权 |
| 视频格式不支持 | 硬解不支持该 profile | 尝试 H264 Baseline 或降低分辨率 |
| 有画面无声音 | 音频编码不支持或静音 | 检查 `setAudioEnable`、音频编码类型 |
| UDP 无画面 | 网络 NAT/防火墙 | 改 TCP 或检查 `send_option` |

### 11.3 验证编译

```bash
./gradlew :simpleplayer:installDebug
adb shell am start -n org.easydarwin.easyplayer/.MainActivity
```

---

## 12. 第三方依赖（EasyPlayer）

| 依赖 | 用途 |
|------|------|
| `update-release.aar` | 应用更新 |
| `texturegesture-release.aar` | 手势缩放 |
| Glide 3.7 | 图片加载 |
| OkHttp 3.4 | 网络请求 |
| Otto 1.3.8 | 事件总线 |
| code-scanner | 二维码扫描 |

`simpleplayer` 仅依赖 `library` 与 Support Library，适合作为集成参考。

---

## 13. 版本信息（当前工程）

| 项 | 值 |
|----|-----|
| applicationId | `org.easydarwin.easyplayer` |
| versionName | `1.4.26.0428` |
| versionCode | `14260428` |
| minSdk | 21 |
| targetSdk | 31 |

---

## 14. 参考链接

- 项目 README：[README.md](../README.md)
- Android RTSP 专用版下载：http://app.tsingsee.com/EasyRTSPlayer
- EasyDarwin 社区：https://www.easydarwin.org

---

## 15. 开发检查清单

集成前请确认：

- [ ] 已将 `library` 模块或 AAR 加入工程
- [ ] `jniLibs` 中对应 ABI 的 `.so` 已打包进 APK
- [ ] 已声明 `INTERNET` 权限
- [ ] UI 使用 `Surface` 或 `TextureView`，且在 Surface 可用后调用 `start()` / `play()`
- [ ] `Activity.onDestroy` 或 `Fragment.onDestroyView` 中调用 `client.stop()`
- [ ] RTSP URL、TCP/UDP、用户名密码与设备端一致
- [ ] 多路场景评估设备解码能力

---

*文档基于当前仓库源码整理。Native 层与 KEY 校验逻辑以实际 `libEasyRTSPClient.so` 行为为准。*
