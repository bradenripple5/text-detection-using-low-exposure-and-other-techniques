# Low-Exposure Camera Sample

A minimal Camera2 Kotlin app that keeps a normal preview stream running while letting you grab manual low-exposure still frames (ideal for plate captures in bright conditions). Preview + frame acquisition reuse the same session, mirroring the snippet you provided.

## Features
- Camera2 preview on a `TextureView` with continuous AF/AE.
- Manual still capture path that temporarily stops repeating preview, sends a single request with custom exposure/ISO, then resumes preview.
- UI seek bars to tweak exposure time (0.25 ms - 4 ms) and ISO (100 - 1600) before triggering a capture.
- Hooks into the `ImageReader` callback for a one-off capture so you can process the resulting `Image` however you like.

## Running it
1. Open the `manual-low-exposure-camera` folder in Android Studio (Giraffe+ recommended) and let it sync.
2. Enable a device/emulator that supports Camera2 (physical device strongly recommended because HALs often block manual control on emulators).
3. Build & run the `app` configuration.
4. Allow the camera permission, aim at your subject, tweak exposure/ISO sliders, and tap **Capture Low-Exposure Frame** to grab a plate-optimized frame.

You can also build from the command line:

```bash
cd manual-low-exposure-camera
./gradlew assembleDebug
```

The manual capture logic lives in `app/src/main/java/com/example/lowexposurecamera/MainActivity.kt` inside `captureLowExposurePlateFrame`, directly translating the template you shared.

## Large files kept out of git
- `app/libs/opencv-release.aar` is intentionally gitignored. Re-download it from the official OpenCV Android SDK release assets:
  - OpenCV Android setup guidance: https://opencv.org/opencv4android-usage-models/
  - Official release downloads: https://github.com/opencv/opencv/releases
  - After extracting the SDK zip, copy `OpenCV-android-sdk/sdk/java/opencv-release.aar` into `app/libs/opencv-release.aar`.
- `.gradle/`, `.gradle-cache/`, `build/`, `app/build/`, and generated APKs are also gitignored. They are recreated by Gradle after clone; run `./gradlew assembleDebug`.
