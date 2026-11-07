# IRIS Lens 2.0 — Android camera assistant MVP

IRIS Lens is a native Android camera project designed for Android 10 devices, including the Redmi 9 Active. Scene analysis and face detection run on-device; frames are not uploaded to a server.

## Version 2.0 additions

- **Composition score (0–100):** a lightweight heuristic score combining sampled frame brightness, scene-label confidence, approximate subject category, and orientation suitability. It is a guidance indicator, not a trained aesthetic-quality model.
- **Portrait / landscape guidance:** recommends portrait framing for person-focused scenes and landscape framing for common wide-scene labels.
- **Face placement guidance:** detects a face and checks its approximate position against an upper-third/central placement target.
- **Background analysis:** shows the strongest ML Kit scene labels and gives a basic clutter/simplification suggestion based on label variety. This is a proxy, not semantic segmentation.
- **Cinematic colour looks:** Natural, Cinema and Warm. Cinema/Warm are applied to the saved photograph; Natural preserves the decoded image without a colour transform.
- **Capture pipeline:** captures to memory, applies the selected look locally, then saves the JPEG to `Pictures/IRIS Lens`.
- **Device-aware controls:** zoom range and exposure-compensation range are taken from the active camera where available.
- **Frame processing:** throttled analysis and `KEEP_ONLY_LATEST` backpressure limit work on lower-powered phones.

## Existing capabilities

- CameraX live preview and still-photo capture.
- ML Kit image labelling and fast face detection.
- Rule-of-thirds grid overlay.
- Approximate luminance sampling.
- Zoom and exposure compensation controls.
- Dark, minimal camera interface.

## Build instructions

1. Install Android Studio (stable) on a computer.
2. Extract this ZIP and open the `IRIS_Lens_Android` folder in Android Studio.
3. Allow Gradle sync. Install Android SDK Platform 35 if prompted.
4. Connect the Android phone with USB debugging enabled, or use an emulator with a camera.
5. Select the device and press **Run**.
6. To create an APK, choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

The first Gradle sync requires internet access to download Android Gradle Plugin, CameraX and ML Kit dependencies. No Gradle wrapper is bundled in this source archive; Android Studio can import the project and configure Gradle, or a wrapper can be generated from a local Gradle installation.

## Compatibility and limitations

- `minSdk 23`; target SDK 35. Android 10 (API 29) is supported by the declared API level and CameraX/ML Kit dependencies, but this project has not been compiled or tested on the Redmi 9 Active in this environment.
- The composition score is heuristic. It does not measure artistic quality, horizon alignment, leading lines, motion blur, eye sharpness, or exact background separation.
- Background clutter is estimated from the number of distinct ML Kit labels, not pixel-level segmentation.
- The three looks are simple colour-matrix presets, not professional RAW colour grading. Filtered shots are decoded to RGB_565 to reduce peak memory use on entry-level phones; this can slightly reduce colour precision compared with Natural.
- Camera zoom/exposure ranges depend on hardware. Some camera modules expose only limited controls.
- Manual focus, white balance, RAW capture, portrait blur, gallery browsing and video are not implemented yet.
- A compiled APK is not included. It must be built with Android SDK/Gradle in Android Studio.

## Project structure

- `app/src/main/java/com/iris/lens/MainActivity.kt` — CameraX, ML Kit analysis, scoring, looks and photo saving.
- `GuideOverlay.kt` — rule-of-thirds composition grid.
- `app/src/main/res/layout/activity_main.xml` — camera interface.
- `app/src/main/res/values/styles.xml` — visual theme.
