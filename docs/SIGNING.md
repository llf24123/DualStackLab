# 正式签名（release）配置

本项目的 `release` 变体默认**不签名**（`minifyEnabled false`，用于内测）。要出可分发、可升级的正式包，按下面配一次，之后每次 `assembleRelease` 自动签名。

## 1. 生成 keystore（只做一次，然后备份）

```bash
keytool -genkeypair -v \
  -keystore dualstack-release.jks \
  -alias dualstack \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -storetype PKCS12
```

记下 alias 和两个密码。**`.jks` 丢了 = 老用户永远收不到升级包**（只能换 applicationId 重来），务必离线备份两份。

## 2. 本地配置

```bash
cp keystore.properties.example keystore.properties
```

填入：

```properties
storeFile=/absolute/path/to/dualstack-release.jks
storePassword=你的密码
keyAlias=dualstack
keyPassword=你的密码
```

`keystore.properties` 与 `*.jks` 已列入 `.gitignore`。文件不存在时构建自动回退为不签名的 release 包，不会报错。

## 3. 构建与校验

```bash
./gradlew assembleRelease
/root/android-sdk/build-tools/36.1.0/apksigner verify --print-certs -v \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

合格标准：

- `Verified using v2 scheme (APK Signature Scheme v2): true`（minSdk 26 只需要 v2，v1 由 AGP 按 minSdk 自动决定，本机实测为 false 是正常的）
- 证书 `CN=` 是你自己的名字，**不是** `CN=Android Debug`
- `aapt2 dump badging app-release.apk | grep -i debuggable` 无输出

## 4. CI 里签名（GitHub Actions）

仓库 Secrets 需要四项：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 dualstack-release.jks` 的结果 |
| `KEYSTORE_PASSWORD` | storePassword |
| `KEY_ALIAS` | dualstack |
| `KEY_PASSWORD` | keyPassword |

`base64 -w0` 在 macOS 上是 `base64 -i file`。配好后打 tag（`git tag v0.6.14 && git push origin v0.6.14`）即自动出签名包并附到 Release。

> 为什么不能继续用 debug 签名对外发：`CN=Android Debug` 这把私钥是 Android SDK 自带的、人人都有，任何人可以伪造"签名一致"的包冒充你；且 debug 包 `android:debuggable="true"`，拿到设备可直接 `run-as` 进沙盒读私有数据。
