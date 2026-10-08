# EasyPlayer #
EasyPlayer-**RTSP Android** 播放器是由[EasyDarwin团队](https://www.easydarwin.org "EasyDarwin团队")开发和维护的一个完善的RTSP流媒体播放器项目，视频编码支持**H.264**，**H.265**，**MPEG4**，**MJPEG**，音频支持**G711A**，**G711U**，**G726**，**AAC**，支持RTSP over TCP/UDP协议，支持**硬解码**，是一套极佳的安防流媒体平台播放组件！EasyPlayer-RTSP Android 安卓版本经过了很多年的发展和迭代，已经非常稳定、完整，功能包括：直播、录像、抓图，支持指令集包括armv7a、armv8a、x86，应该说是目前市面上功能性、稳定性和完整性最强的一款RTSP播放器！

## 工程结构 Project structure ##

	EasyPlayer_Android
	|-EasyPlayer            APP module
	|-simpleplayer          simplest demo module
	|-library               library module

## 功能特点 ##

- [x] 超低延迟的rtsp播放器；
- [x] 完美支持多窗口多实例播放；
- [x] 支持RTSP TCP/UDP模式切换；
- [x] 支持播放端，buffer设置；
- [x] 秒开播放；
- [x] 支持自定义播放布局;
- [x] 编解码、显示、播放源码全开放，更加灵活;
- [x] 支持播放过程中，'实时静音/取消静音';
- [x] 高效的延时追帧策略；
- [x] [快照]支持播放过程中，**随时快照**；
- [x] [录像]支持播放过程中，**随时录像**；

## 编译方法 ##

直接用 Android Studio 打开本仓库根目录（`settings.gradle` 所在目录），完成首次
Gradle 同步后，点击工具栏的锤子图标（Build > Make Project）即可编译工程。
工程自带 Gradle Wrapper 和三个 ABI 的原生库；日常编译不需要单独安装 NDK/CMake，
也不需要下载、解压或编译 FFmpeg。只在需要重建原生库时，才按下方文档安装 NDK r28+。
如果 Android Studio 提示缺少 Android SDK Platform 35，按提示通过 SDK Manager 安装即可。

原生库已改为 FFmpeg 源码构建并支持 Android 16 KB 页大小。重建方法与模拟器测试见
[原生库与 16 KB 适配](docs/NATIVE_16KB.md)。


## 最新版本下载 ##

- Android RTSP专用版：[http://app.tsingsee.com/EasyRTSPlayer](http://app.tsingsee.com/EasyRTSPlayer)

## 获取更多信息 ##

<img width="185" height="184" alt="QQ_1756176182992" src="https://github.com/user-attachments/assets/1fb42348-2bf8-4ee5-9cb0-610385067c99" />

技术资料：关注‘EasyPlayer’公众号

EasyDarwin社区：[www.EasyDarwin.org](https://www.easydarwin.org)

Copyright &copy; EasyDarwin.org 2012-2024
