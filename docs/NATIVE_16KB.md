# FFmpeg 与 Android 16 KB 页大小适配

## 实现

三个 ABI（`arm64-v8a`、`armeabi-v7a`、`x86`）现在只包含：

- `libEasyRTSPClient.so`：保留原有 RTSP 拉流库。
- `libEasyPlayerFFmpeg.so`：新 JNI 实现，静态链接 FFmpeg 7.1.5 的
  avcodec、avformat、avutil、swscale、swresample。

移除了旧的 libproffmpeg、libAudioCodecer、libVideoCodecer、libyuv_android。
Java 的 AudioCodec、VideoCodec、EasyMuxer2 接口保留。JNIUtil 只保留当前
实际调用的 `yuvConvert(..., 4)`（NV12 → I420）；未使用的裁剪、缩放、矩阵旋转
接口和 yuvRotate 方法已移除。没有引入 avfilter。

音频输出为 S16 PCM；视频软解为 H264/H265，输出紧凑 I420 并经 swscale 转 RGBA
显示到 ANativeWindow。DirectByteBuffer 在回调后释放，调用方若要保留数据必须拷贝。
录像保持压缩视频写入 MP4、PCM 转 AAC 的原有流程，关闭时排空编码器、写尾部；
支持视频独占录像、单声道/双声道音频。现有 Java 录像接口只提供一个时间戳，
因此继续使用 PTS=DTS；包含 B 帧的录像时间戳支持不在本次范围内。

FFmpeg 源码使用官方 7.1.5 原始压缩包，固定 SHA-256。配置不启用 GPL/nonfree，
许可证与来源说明随 AAR/APK 的 assets/ffmpeg 一起分发。

## 重建原生库

需要 Python 3.12+、CMake 3.18+、make、curl，以及 Android NDK r28 或更新版本。
在工程根目录执行：

```bash
python3 scripts/build-native.py --ndk /path/to/android-sdk/ndk/28.2.13676358
```

只重建一个 ABI：

```bash
python3 scripts/build-native.py --ndk /path/to/ndk --abis arm64-v8a
```

官方下载包在 `third_party/ffmpeg/ffmpeg-7.1.5.tar.xz`，随仓库受控，构建优先
使用本地包并校验 SHA-256；包缺失时才从官方地址下载。解压源码、静态库和
中间产物在 `.native/`（不提交）。最终 .so 写回
`library/src/main/jniLibs/<ABI>/`，这些预编译产物随仓库分发，正常 Gradle 构建无需
下载 FFmpeg。原生库重建在下载包已存在时也无需联网。版本变化使用独立缓存
目录，避免混用旧对象文件。

ARM 保留 NEON 优化。旧的 32 位 x86 在 NDK r28 下编译内联汇编存在寄存器不足，
该 ABI 使用 C 实现；ARM64 的模拟器运行测试不代表 32 位 ABI 的运行验证。

新库使用 `max-page-size=16384`、`common-page-size=16384`，保留 RELRO。
Android 构建更新为 AGP 8.7.3、Gradle 8.9、compileSdk 35，并保留 minSdk 21 / targetSdk 31。
使用 JDK 17 或 21 构建（Gradle 8.9 不支持使用 JDK 23 运行）：

```bash
./gradlew :EasyPlayer:assembleDebug :simpleplayer:assembleDebug :library:assembleRelease
```

## 对齐检查

```bash
python3 scripts/check-native-alignment.py library/src/main/jniLibs
python3 scripts/check-native-alignment.py simpleplayer/build/outputs/apk/debug/simpleplayer-debug.apk
$ANDROID_HOME/build-tools/35.0.0/zipalign -c -P 16 -v 4 simpleplayer/build/outputs/apk/debug/simpleplayer-debug.apk
```

检查脚本覆盖所有 64 位 .so 的 LOAD 对齐、地址/文件偏移一致性，以及 RELRO
向上取整到 16 KB 后是否覆盖需要写入的数据。原有 RTSP 库的 RELRO 末尾留有空隙，
后续可写 LOAD 从下一页开始；此布局没有旧 FFmpeg 库中保护区和可写数据共享页面的问题。
单纯修改 ELF 的 p_align、关闭 RELRO 或只做 zipalign 都不能替代重编译。

## 模拟器回归

启动 ARM64 16 KB 系统镜像。确认 `adb shell getconf PAGE_SIZE` 返回 `16384`。
如果旧版 avdmanager 创建的 AVD 出现 `target=android-0`，需在 AVD 的 .ini 中设置
对应的真实 API 级别；本次安装的 API 37 镜像被旧工具错误识别为 0，修正后正常启动。

```bash
./gradlew :simpleplayer:assembleDebug :simpleplayer:assembleDebugAndroidTest
python3 scripts/test-emulator.py --serial emulator-5556 \
  --rtsp-url 'rtsp://your-server/path' --software true
python3 scripts/test-emulator.py --serial emulator-5556 \
  --rtsp-url 'rtsp://your-server/path' --software false
```

脚本安装 SimplePlayer 和 instrumentation APK（均使用现有
`org.easydarwin.easyplayer` applicationId，会替换设备上的同包名 Demo），运行：

- NV12 → I420 各平面字节校验、越界输入检查。
- G711A/U 已知样本、G726、AAC 解码。
- H264/H265 解码后的尺寸、I420 长度、颜色值，以及各 20 次创建/释放。
- H264/H265 + AAC 录像；ffprobe 必须完整解码 20 帧、音视频时长均为 2 秒。
- 如提供 RTSP URL：等待播放成功，检查指定解码模式，等关键帧开始写入后录制
  10 秒并解码输出文件，避免把等待关键帧的时间计入录像时长。

结果和录像默认保存到 `.native/test-results/`。测试素材是自行生成的 64×48 红色
单帧 H264/H265 和 8 kHz 正弦波 AAC，不包含摄像头录像。

也可以直接启动 Demo 指定链接，不必把摄像头地址写死在源码中：

```bash
adb shell am start -n org.easydarwin.easyplayer/org.easydarwin.player.simpleplayer.MainActivity \
  --es rtsp_url 'rtsp://your-server/path' --ez software true
```

切换启动参数前请先停止原 Activity，或使用界面上的软解/硬解开关。

## 本次验证结果（2026-10-08）

FFmpeg 7.1.5、NDK 28.2.13676358；ARM64 API 37 的
`google_apis_ps16k` 模拟器，运行时页大小为 16384 字节。
使用本次指定的 RTSP 链接，实际流为 1920×1080 H.265 + AAC。

- 软解、硬解的完整 instrumentation 均 PASS，包括播放成功和解码模式检查。
- 等关键帧开始写入后录像：软解 252 帧 / 10.088 秒；硬解 249 帧 / 9.967 秒。
  两份录像的视频与 AAC 音频经 ffprobe 完整解码，无解码错误。
- 合成 H264/H265 + AAC 录像均为 20 帧，音视频时长均为 2.000 秒。
- YUV 平面转换、越界输入、G711A/U、G726、AAC、H264/H265 像素校验及
  各 20 次解码器创建/释放均通过。
- 两个 Demo APK 和 release AAR 的 64 位 ELF 对齐检查通过；两个 APK 的
  `zipalign -c -P 16` 检查通过。

本地验证报告、instrumentation 输出和录像保存在 `.native/test-results/`，
摄像头录像不提交到仓库。32 位 ABI 已重建，尚未做运行验证。

参考：[Android 16 KB 指南](https://developer.android.com/guide/practices/page-sizes)、
[FFmpeg 官方发布](https://ffmpeg.org/download.html)。
