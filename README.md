# WorkoutPlanner (Android, Jetpack Compose)

Features:
- Daily reminders
- Rest timer
- Set checkboxes, weight (kg), RPE input
- Weekly summary with auto-progression suggestions
- CSV export

## GitHub Actions
- **Debug build**: `.github/workflows/android-ci.yml` builds a debug APK on each push and uploads it as an artifact.
- **Signed Release build**: `.github/workflows/release.yml` builds a **signed** release APK when you push a tag like `v1.0` or run it manually.

### Secrets required for release
Create these repo **Actions secrets**:
- `KEYSTORE_BASE64` – base64 of your `keystore.jks`
- `KEYSTORE_PASSWORD` – keystore password
- `KEY_ALIAS` – key alias (e.g., `upload`)
- `KEY_PASSWORD` – key password

Generate a keystore (one-time):
```bash
keytool -genkeypair -v -keystore keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 keystore.jks > keystore.b64   # copy contents into KEYSTORE_BASE64 secret
```

To make a release:
```bash
git tag v1.0
git push origin v1.0
```
Check Actions → artifacts or Releases → assets for the APK.

## One-click Signed APK build (phone sideload)
- Workflow: `.github/workflows/phone-release-apk.yml`
- Add secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Run from Actions or push a tag like `apk-v1`. Download artifact `phone-release-apk`.
