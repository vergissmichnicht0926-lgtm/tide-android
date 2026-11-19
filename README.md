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

## 许可证

MIT，详见 [LICENSE](LICENSE)。

© 2026 树云铃
