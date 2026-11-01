# cuboai-local

Repository: [github.com/samhunt/cuboai-local](https://github.com/samhunt/cuboai-local)

Android app for a CuboAI camera you own: live HEVC+AAC, night light, sleep mode, and lullabies. Live video and controls stay on your LAN. Credentials are stored encrypted on the phone.

Independent interoperability software. Not affiliated with CuboAI / Cubo or ThroughTek.

## What stays on the device

- Live video is UDP on your Wi-Fi, muxed to MPEG-TS on `127.0.0.1` for the on-device player.
- Camera credentials (UID, `admin@…`, password, optional LAN IP) are stored in encrypted prefs. Android backup of prefs/files is disabled.
- Cubo account email/password and cloud tokens are **not** saved. After a successful fetch they are discarded.
- Cry / rollover / face-cover alerts are Cubo cloud products and are **not** in this app.

## Getting camera credentials

Pick one path on the login screen:

1. **Cubo account (once).** Sign in with your Cubo email and password (SMS / authenticator code if the account has 2FA). Login uses the [AWS Cognito Android SDK](https://github.com/aws-amplify/aws-sdk-android) plus Cubo’s `user/cameras` API. The app copies the TUTK UID / account / password (and a LAN IP if Cubo has one), then drops the Cubo session. After that, **Connect on LAN** never calls Cubo again.
2. **Manual.** Paste credentials you already have from pairing (`GET https://app-api.getcubo.com/prod/user/cameras`) or from another local integration.

If Cubo does not return a LAN IP, type the camera’s IPv4 address yourself. You still need to be on the same Wi-Fi as the camera.

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Requires JDK 17, Python 3.14 (Chaquopy bytecode), the Android SDK, and network on the first build so Gradle can download the LAN protocol modules.

## Release (GitHub Actions)

Push a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

CI builds a **signed** release APK and attaches it to a GitHub Release named `cuboai-local v0.1.0`.

### GitHub secrets

Add these under **Settings → Secrets and variables → Actions** on [samhunt/cuboai-local](https://github.com/samhunt/cuboai-local). There is no Cubo/cloud token.

| Secret | What it is |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Your upload keystore, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias (example: `cuboai-local`) |
| `SIGNING_KEY_PASSWORD` | Key password (often the same as the store password) |

`GITHUB_TOKEN` is created by GitHub for the workflow. Do not add it.

Create a keystore once and keep a backup **off GitHub**:

```bash
keytool -genkeypair -v \
  -keystore cuboai-local.jks \
  -alias cuboai-local \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=cuboai-local"

base64 -w0 cuboai-local.jks   # macOS: base64 -i cuboai-local.jks | tr -d '\n'
```

Paste the one-line base64 into `SIGNING_KEYSTORE_BASE64`. Losing this keystore means you cannot update the same Play/sideload identity later.

Local signed builds can use a gitignored `keystore.properties` copied from `keystore.properties.example`.

## License

MIT. See [LICENSE](LICENSE). The same text is bundled in the APK (`assets/LICENSE`) and shown in the app under **MIT License**.

This is independent interoperability software for a camera you own. It is not affiliated with, endorsed by, or supported by CuboAI / Cubo or ThroughTek. Third-party components (AndroidX, Compose, Media3, Chaquopy, Python, AWS SDK, OkHttp) keep their own licenses; see [NOTICE](NOTICE).
