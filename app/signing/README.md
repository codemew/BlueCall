# Release signing

`bluecall-test.jks` is a **public test key**. Its passwords are in `app/build.gradle.kts`.

- Release builds and GitHub releases are signed with it, so each new BlueCall APK installs over the previous one.
- Anyone with this repository can sign an APK with the same key. Use it only for personal testing, never for an app store or wide distribution.

## Switching to a private key

1. Create a key:

   ```bash
   keytool -genkeypair -keystore bluecall-release.jks -alias bluecall -keyalg RSA -keysize 2048 -validity 10000
   ```

   Keep it **outside** the repository and back it up. If you lose it, users can't update to builds signed with a new key.
2. Local builds: set these before `./gradlew assembleRelease`:
   - `BLUECALL_KEYSTORE` (path to the `.jks`)
   - `BLUECALL_KEYSTORE_PASSWORD`
   - `BLUECALL_KEY_ALIAS`
   - `BLUECALL_KEY_PASSWORD`
3. GitHub releases:
   1. Add the keystore as a base64 secret.
   2. Add the three passwords and alias as repository secrets.
   3. In `.github/workflows/release.yml`, decode the keystore to a file and export the four variables above.

Phones that have the test-signed build installed must uninstall it once before installing a build signed with the new key.
