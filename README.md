# Tide（汐语）Android 客户端

汐语 Tide 的 Android 原生客户端，Java + Gradle 构建，界面与聊天逻辑全部手写，零第三方依赖。

## 环境要求

- JDK 17
- Android SDK（compileSdk 34 / minSdk 24 / targetSdk 34）
- Gradle 8.2（用 `gradle` 或 `gradlew` 均可）

## 构建

先准备 `local.properties`（不入库）：

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

然后：

```bash
./gradlew :app:assembleDebug
```

首次构建需要联网拉依赖。若你的环境无法直连 Maven 仓库，可以预先把依赖灌进 Gradle 缓存，
之后加 `--offline` 构建。

打 release 包把任务换成 `:app:assembleRelease`，产物在：

```
app/build/outputs/apk/release/app-release.apk
```

## 签名

- `debug` 使用仓库内的 `debug.keystore`（公开的调试签名，不代表任何正式身份）。
- `release` 使用本机 keystore，路径与口令通过 `local.properties` 的 `tide.release.store` 等字段注入。
- 仓库内**不含**正式签名文件，`local.properties` 也不入库；没有本地配置时 release 包不会签名。

## 界面

- 登录页：填网关地址、账号与密码，带邀请码入口和登录／注册切换；开启应用锁时，启动先进 6 位密码解锁页。
- 会话列表：按最近消息排序的单聊与群聊列表，顶部显示连接状态，可从浮层发起单聊、创建群聊或用邀请链接加群。
- 聊天页：文字、图片、文件、语音（按住录音，松开发送）与表情包面板；群聊可进群资料页。
- 设置页：个人资料、账号安全（应用锁、指纹解锁、允许截屏、通知内容）、登录设备、关于与退出登录。

## 权限说明

`app/src/main/AndroidManifest.xml` 里共申请 8 项权限，用途如下：

| 权限 | 用途 | 授权方式 |
| --- | --- | --- |
| `INTERNET` | 访问网关：登录、收消息、维持 SSE 实时流 | 普通权限，安装即授予 |
| `ACCESS_NETWORK_STATE` | 读取网络连通状态，用于连接状态提示与断线重连 | 普通权限，安装即授予 |
| `RECORD_AUDIO` | 录制语音消息 | 危险权限，首次录音时弹窗申请 |
| `POST_NOTIFICATIONS` | 新消息通知（Android 13 及以上） | 危险权限，系统弹窗申请 |
| `FOREGROUND_SERVICE` | 以前台服务保持实时连接 | 普通权限，安装即授予 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14 起要求的服务类型声明，子类型为「保持与私人 AI 助理的长连接」 | 普通权限，安装即授予 |
| `USE_BIOMETRIC`、`USE_FINGERPRINT` | 应用锁的指纹解锁（API 28 及以上可用，低版本只用密码） | 普通权限，安装即授予，需在账号安全里手动开启 |

按 Android 的分级，上表只有 `RECORD_AUDIO` 与 `POST_NOTIFICATIONS` 属于危险权限。拒绝录音后文字、图片、文件、表情照常可用；拒绝通知后应用内消息不受影响，只是不再弹提醒。

Manifest 里没有 `<queries>` 节点，也没有申请 `QUERY_ALL_PACKAGES` 这类读取已安装应用的权限，客户端不枚举手机上的其他应用。

## 已知限制

- 只能侧载安装：未上架任何应用商店，只能手动安装 APK；部分机型会提示「未知来源」或对安装包给出风险提示，这是系统对非商店来源的默认动作。
- 后台连接可能被冻结：实时消息依赖前台服务里的一条 SSE 单流，锁屏较久或系统省电策略介入后进程会被冻结，消息可能延迟到解锁、应用回到前台后才收到。
- 应用锁没有找回路径：忘记 6 位密码后没有找回或重置入口，只能清除应用数据重新登录；连续输错 5 次同样会清除本机数据。本地聊天缓存随之清空，服务端消息不受影响。
- 不支持多账号：一个安装只保留一份登录状态，换账号要先退出再登录。
- 通知内容默认显示：锁屏和通知栏会带上消息正文预览，可在「设置 → 账号安全 → 通知内容」里关掉，关掉后只提示「有新消息」。

## 许可证

MIT，详见 [LICENSE](LICENSE)。

© 2026 树云铃
