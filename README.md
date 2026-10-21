# 安卓改定位

可独立运行的 Android 模拟定位应用。使用系统开发者选项授予的模拟定位能力，通过 `LocationManager` 的 GPS 测试提供者持续提交 WGS-84 坐标。无需 Root，运行时不依赖电脑或 Python 后端。

## 项目内容

- `app/`：主 App，固定位置、手动路线、虚拟摇杆、收藏与历史、诊断和前台服务。
- `probe/`：独立 GPS 读取测试 App，包名与主 App 不同，用来验证系统是否真正提供模拟坐标。
- `docs/`：旧项目审查、使用和测试记录。
- `CHANGELOG.md`：版本更新日志与版本号规则。

地图使用 MapLibre Native Android 和 OpenFreeMap Liberty 样式，界面显示 OpenStreetMap 与 OpenFreeMap 署名。地图瓦片需要联网；正式分发时请评估地图服务的可用性和[使用条款](https://openfreemap.org/tos/)。内部坐标一律为 WGS-84，地图边界集中处理坐标转换。

## 构建

需要 JDK 17、Android SDK Platform 36、Build Tools 36、Platform Tools。项目自带 Gradle Wrapper 8.13，AGP 8.13.2，Kotlin 2.2.20。首次构建需可访问 Google Maven 和 Maven Central。已在此 Mac 的 `.tools/` 与 `.sdk/` 安装工具链；这两个目录属于本地下载缓存，不是源码的一部分。

```sh
cd 安卓改定位
export JAVA_HOME="$PWD/.tools/jdk/jdk-17.0.20.1+1/Contents/Home"
export ANDROID_HOME="$PWD/.sdk"
./gradlew :app:assembleDebug :app:testDebugUnitTest :probe:assembleDebug
```

若使用自行安装的 SDK，在 `local.properties` 中写入 `sdk.dir`，并将 `JAVA_HOME` 设为 JDK 17。主 APK 生成路径：`app/build/outputs/apk/debug/app-debug.apk`；独立读取器：`probe/build/outputs/apk/debug/probe-debug.apk`。

## 手机使用

1. 在手机系统设置中打开开发者选项和 USB 调试；安装主 APK 后，在开发者选项中的“选择模拟位置信息应用”选择“安卓改定位”。
2. 打开主 App，确认顶部显示“模拟定位已授权”。在“地图”选点或输入经纬度，点击“开始固定定位”。请在“诊断”页确认 API 返回和最近提交坐标。
3. 在“路线”页点地图添加至少两个不同途经点，设速度后开始。可暂停、继续、调整速度、停止。路线结束后保持终点位置，直到手动停止。
4. 在“路线”页进入虚拟摇杆，按住方向键持续移动，松开停在当前位置。路线运行时进入摇杆会保存路线进度；退出后可继续原路线。
5. 通过通知栏的“停止模拟”或 App 内“停止”移除测试 GPS 提供者。停止后用独立读取器检查系统是否恢复真实定位。

安装独立读取器后，授予“精确位置”权限，再点击“读取系统 GPS”。它显示实际从 `LocationManager.GPS_PROVIDER` 读到的经纬度以及系统的 `isMock` 标记。主 App 的“已提交”只说明 API 调用返回成功；请以独立读取器为系统级验证依据。

## 设备和微信测试

荣耀 200 Pro 真机和微信测试步骤见 [真机测试清单](docs/真机测试清单.md)。系统模拟定位成功与微信是否采用坐标是两项独立结论。软件不修改微信、不隐藏模拟标记。
