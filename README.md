# 遥控麦 · RemoteMic for Android

把**小米蓝牙遥控器 2 / 2 Pro（RC001 / RC003）**变成手机的语音输入与遥控器：
按住语音键说话 → 手机端离线语音识别 → 文字写入当前输入框；方向键、OK、返回、主页、菜单、TV、音量键可自定义映射。

协议实现移植自 [HD838A/remote-mic-app](https://github.com/HD838A/remote-mic-app)（GPL-3.0）的公开技术文档与源码分析，本工程为 Android 端重写。

## 状态

- M1 连接与音频链路：BLE 扫描/连接、ATVV 能力协商、语音开关、ADPCM 解码播放
- M2 离线识别与文字注入：sherpa-onnx + SenseVoice，无障碍服务写文本
- M3 按键映射、记录、引导与设置

## 构建

```bash
./scripts/fetch-sherpa-aar.sh      # 下载 sherpa-onnx AAR 到 app/libs/
gradle :app:assembleDebug          # 或直接看 GitHub Actions 产物
```
