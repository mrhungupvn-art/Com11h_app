# COM11H Android v1.3.0

## Build in Android Studio
1. Open the `android` folder as the project root.
2. Use JDK 17.
3. Use Gradle 8.9 (AGP 8.7.3).
4. Sync Gradle and run the `app` configuration on the phone.

## Build APK from GitHub
The repository root includes `.github/workflows/build-android.yml`.
After pushing to `main`, GitHub Actions builds:
`android/app/build/outputs/apk/debug/app-debug.apk`

The APK is published as the workflow artifact `COM11H-Android-v1.3.0-debug`.

## Verified business flow to preserve
Login -> Menu -> Cart -> Order Preview -> Create Order -> QR -> Payment -> Stock reduction -> Delivery -> Customer delivery confirmation -> Points/Lucky Code.

Payment must be confirmed by the server before stock is reduced.
