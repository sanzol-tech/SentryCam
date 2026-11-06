# SentryCam

Takes photos on its own, silently, at an interval you choose (Photo mode), or records
continuous video in files of 5, 10 or 15 minutes, with optional delayed start and optional
audio (Video mode). Photos go to `Pictures/SentryCam`, videos to `Movies/SentryCam`. Optional
limits, any size the camera and encoder support, Camera Preview to point the phone. Works offline
with the screen off; never deletes photos or videos. Plain Java, no third-party runtime dependencies, no network permission. UI in
English and Spanish.

| Item           | Value                                       |
|----------------|---------------------------------------------|
| Package        | `ar.sanzol.sentrycam`                       |
| Application ID | `ar.sanzoltech.sentrycam` (debug: `.debug`) |
| Toolchain      | AGP 9.4.1, Gradle 9.8.2, Java 21            |
| SDK            | compile/target 37, min 31                   |

## Layout

The Git repository is this project folder. The surrounding workspace holds what
never enters Git:

```
SentryCam\
  make_release.bat        builds and stages a signed release
  keystore\               create_sentrycam_keystore.bat + sentrycam-release.jks
  release\<version>\      staged APK + mapping.txt
  docs\                   private project documents
  sources\SentryCam\      this project (Git repository)
```

In the project, `MainActivity` is the operation screen, `PreviewActivity` the Camera Preview,
`SettingsActivity` the settings, `CaptureService` the photo foreground service, `CameraController` the Camera2 code and `PhotoStore` the MediaStore writes; Video mode is
`VideoService`, `VideoRecorder` (Camera2 + MediaRecorder), `VideoCaps` and `VideoStore`.
Scheduling, limits, names, estimates and settings validation are pure Java (unit-tested).
Design: [ARCHITECTURE.md](ARCHITECTURE.md).

## Build

```bash
./gradlew testDebugUnitTest lint
./gradlew assembleDebug
```

## Release

1. Once: `keystore\create_sentrycam_keystore.bat` creates `keystore\sentrycam-release.jks`.
   Back it up outside the PC; losing it means the app can never be updated.
2. Once: copy `keystore.properties.example` to `keystore.properties` and set the passwords.
3. Each release: bump `versionCode`/`versionName`, then run `make_release.bat` from the
   workspace (or `./gradlew :app:stageRelease`). It builds the minified release, signs it
   (v2 only), verifies the signature and stages `release\<version>\sentrycam-<version>.apk`
   with its `mapping.txt`. Keep the `mapping.txt` private: it is the only way to read a
   release stack trace.

Without `keystore.properties` the release still builds, minified but unsigned.

## Known limitations

- Capture starts only from the visible app; no boot-time or background auto-start.
- If Android kills the process, the session ends; the app says so and you start again.
- Some devices or regions force a shutter sound; the app does not bypass it.
- A gallery app with cloud backup enabled may upload photos from `Pictures/` and videos from
  `Movies/`.
- Between two video files there is a short gap (usually under a second).
- Many phones reserve their extra lenses for the maker's camera app; then only 1× is offered.

## License

MIT. See `LICENSE`.
