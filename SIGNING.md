# Doorplate Kiosk Signing

This fork intentionally keeps a stable release signing key in the repository so
the Sony TV kiosk APK can be rebuilt and updated without losing the signing
chain again.

Signing files:

- `app/signing/doorplate-release.keystore`
- `app/signing.properties`

Build the release APK with:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew --no-daemon :app:assembleRelease
```

The first migration from an APK signed by another key cannot use `adb install
-r`. Android requires matching package signatures. For that one-time migration,
back up what can be recorded, uninstall `com.vickyleu.doorplatekiosk`, install
the new release APK, then use this same key for all future updates.
