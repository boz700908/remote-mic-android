# 遥控麦 · RemoteMic for Android

把**蓝牙语音遥控器**变成手机的语音输入设备与外接键盘：按语音键说话 → 端上离线识别 → 文字写入当前输入框；
方向键、OK、返回、主页、菜单、TV、音量等按键按机型描述符解码后**注入为真实按键事件**（可在任意 App 里当键盘用）。

- 应用 ID：`com.boz700908.remotemic`　版本：`0.4.0`（debug 构建，未签名发布）
- 最低系统：Android 8.0（API 26）　目标：API 35
- 语言/界面：Kotlin + Jetpack Compose，深色主题 `#0E1116`

协议与按键解码思路参考 [HD838A/remote-mic-app](https://github.com/HD838A/remote-mic-app)（GPL-3.0）
与 [leowzz/axonkey](https://github.com/leowzz/axonkey)（Rust 版按键描述符引擎）。本工程为 Android 端独立重写，
机型描述符为按公开接口事实重新整理的 JSON，不含上游图形资源。

## 功能

| 能力 | 现状 |
| --- | --- |
| BLE 扫描 / 连接 / 断线重连 | 已实现 |
| ATVV 语音链路（能力协商、麦克风开关、ADPCM 解码） | 已实现 |
| 离线语音识别（sherpa-onnx，模型运行时下载） | 已实现 |
| 识别结果写入输入框（无障碍 `ACTION_SET_TEXT`） | 已实现 |
| HID 按键报告解码（12 款机型描述符） | 已实现 |
| 按键注入为真实按键事件（root） | 已实现 |
| 免 root 真键盘注入（Shizuku UserService，按住 / 组合键 / 长按重复） | 已实现 |
| `uinput` 常驻守护（`libuinputd.so`，root 下按住与组合键） | 已实现 |
| 自定义按键映射（inputId → 动作），按机型持久化 | 已实现 |
| 配置档（多套映射一键切换） | 规划中 |

## 支持的机型

`app/src/main/assets/devices/` 下每款一个描述符，共 12 款：

| 描述符 | 说明 |
| --- | --- |
| `rc003` | 小米蓝牙遥控器 2 Pro（RC003），含语音与键盘复合报告 |
| `rc313` / `xm-bt-29` | 小米系列遥控器 |
| `cmiot-a15` | 移动定制款，含 vendor-state 语音边沿报告 |
| `obe-rcu-r5` | OBE 遥控器 |
| `phicomm-db1-r` | 斐讯 DB1-R |
| `hisense-rcf3b65` | 海信遥控器 |
| `tcl-rc802a` / `tcl-rc902n` / `tcl-rc902v` | TCL 系列 |
| `xbox` | Xbox 手柄（HID 报告） |
| `kivo-k18-lite` | USB HID 款（仅记录，Android 端跳过） |

识别不依赖 VID/PID（Android 无 HID 主机 API），而是用设备名、型号字符串与是否存在 HID 服务做文本线索匹配；
匹配不到时回落到通用描述符，仍能解出标准键盘/消费者页按键。

## 按键解码

`device/DescriptorEngine.kt` 按上游 `engine.rs` 语义复刻：

- 报告匹配：前缀/反前缀整包比对、`length` 校验、排除 GATT 流与非 input 报告
- 值提取：`le-u16-slots` / `le-u16-single` / `boot-keyboard` / `bit-field` / `vendor-state`
- 按下判定：滞回双阈值（默认 0.55 / 0.35，可由 `threshold` / `release-threshold` 覆写）
- `state-group` 报告合并为一个状态机

## 语音模型

三档可选，均在应用内下载到 `filesDir/models/<id>/`，识别全部在本机完成，不联网：

| 档位 | 模型 | 特点 |
| --- | --- | --- |
| 默认 | SenseVoice small（中英日韩粤） | 体积小、速度快、带标点 |
| 高精度 | Qwen3-ASR 0.6B int8 | 中文准确率更高，占用更大 |
| 轻量 | Paraformer 中文 | 纯中文、速度最快 |

推理使用 `sherpa-onnx` 2.0.1（AAR 由 CI 下载并缓存）。

## 按键映射

「映射」页列出当前机型描述符里解析出的每个按键，逐个绑成动作；没绑的键保持原样转发。

| 动作串 | 含义 |
| --- | --- |
| `key:CTRL+SHIFT+V` | 发组合键；`F5@800` 表示按住 800 毫秒 |
| `text:你好` | 写进当前焦点输入框（需无障碍） |
| `app:com.tencent.mm` | 启动该包名的主界面（Android 11+ 靠 `<queries>` 可见性） |
| `url:https://example.com` | 仅 http/https，用浏览器打开 |
| `act:back` | 系统动作：`back` `home` `recents` `menu` `vol_up` `vol_down` `mute` `media_play_pause` `media_next` `media_prev` `scroll_up` `scroll_down` `paste` `voice` `none` |
| `none` | 屏蔽该键 |

键名解析不认错就整体放弃（宁可不发，也不发半个组合键）；注入失败返回「未处理」，按键继续走默认转发，不静默丢键。

## 注入通道

注入能力按可用性从强到弱分档，运行时自动选最强的一档：

| 通道 | 前提 | 能力 |
| --- | --- | --- |
| `root · uinput` | root 且 `/dev/uinput` 可写 | 按住、真组合键、长按重复 |
| `root（su input）` | root | 整按；长按用 `input keyevent --longpress` |
| `Shizuku 服务（真键盘）` | Shizuku 已授权 | 按住、真组合键、长按重复 |
| `Shizuku（input 命令）` | Shizuku 已授权 | 整按、`--longpress` |
| 无 root、无 Shizuku | — | 无障碍全局动作（返回/主页/最近任务/滚动）与音量键，不静默丢弃按键 |

「Shizuku 服务」是本应用自己的 UserService（`inject/KeyUserService.kt` + AIDL `IKeyService`）：
由 Shizuku 以 shell 身份拉起独立进程，直接调用 `InputManager.injectInputEvent` 注入原生
`KeyEvent`（`SOURCE_KEYBOARD`）。shell 持有 `INJECT_EVENTS`，所以按键能送到任意应用，
不必等某个输入框拿到焦点。服务内维护「当前按下的键集合」，组合键按修饰键先按、逆序抬起，
长按以 50 ms 周期发 repeat 事件；服务销毁时释放全部按键，避免卡键。

文字注入：无障碍 `ACTION_SET_TEXT`，需在系统设置里启用本应用的无障碍服务。

## 构建

```bash
./scripts/fetch-sherpa-aar.sh      # 下载 sherpa-onnx AAR 到 app/libs/
gradle :app:assembleDebug          # 或在 GitHub Actions 里出包
```

仓库自带 GitHub Actions：推送到 `main` 即构建 `remotemic-debug-apk` 产物。

## 许可证

本工程以 GPL-3.0 发布（因参考项目 `remote-mic-app` 为 GPL-3.0）。
`axonkey` 未声明许可证，本工程只借鉴其「按键描述符 → 解码」的功能性接口事实并重写实现，未复制其代码或资源。
