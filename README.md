# Video to AAC

极简 Android 应用：多选 MP4 视频，提取音频转 AAC，统一响度到 -14 LUFS。

## 功能

- 多选 MP4/MOV/MKV 视频文件
- 提取音频并转为 AAC 格式 (128kbps)
- 响度归一化至 -14 LUFS (EBU R128)
- 采样率可选：原始 / 48000 / 44100 / 22050 / 16000 Hz
- 输出到手机 Downloads 文件夹

## 技术栈

- Kotlin + Jetpack Compose
- ffmpeg-kit-full (Android FFmpeg 封装)
- 文件选择：Android SAF (Storage Access Framework)
- 输出：MediaStore API → Downloads

## 构建

```bash
./gradlew assembleRelease
```

APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

## CI

推送即自动构建，APK 从 GitHub Actions Artifacts 下载。
