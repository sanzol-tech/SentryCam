# SentryCam — Architecture

Single-module Java app, framework APIs only.

## Components

| Class                                                                                                  | Responsibility                                                                                                |
|--------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `PreviewActivity`, `PreviewCamera`, `PreviewGeometry`                                                  | Camera Preview screen; its camera session (hand-over to Start); size and fit rules (pure, tested)             |
| `LensCatalog`, `LensProbe`                                                                             | Back-lens choices (pure, tested); `LensProbe.target`: the one camera/zoom rule for Preview, capture, Settings |
| `MainActivity`                                                                                         | Operation screen: mode, status, two cards, three context rows, Preview and Start/Stop                         |
| `SettingsActivity`                                                                                     | Settings of the chosen mode (read-only during a session), shared items, theme                                 |
| `CaptureService`                                                                                       | Foreground service of type camera: session lifecycle, schedule, limits, wake lock, notification, recovery     |
| `VideoService`                                                                                         | Video mode: foreground service (camera, plus microphone with audio); delay, files, limits, battery, heat      |
| `VideoRecorder`, `VideoCaps`, `VideoStore`                                                             | Camera2 + MediaRecorder on a persistent surface; real video sizes/bitrate; MP4 files in Movies/SentryCam      |
| `VideoSettings`, `VideoPlan`, `VideoNames`                                                             | Video settings validation, bitrate/estimate/file length, file names (pure, tested)                            |
| `PowerGuard`, `PowerLimits`                                                                            | Battery and heat limits for both modes (10 %, thermal CRITICAL); one rule                                     |
| `CameraController`                                                                                     | Camera2: camera selection, sizes, session, metering stream, still capture, timeouts, errors                   |
| `PhotoStore`                                                                                           | MediaStore writes to `Pictures/SentryCam`, photo count, incomplete-write cleanup, free space                  |
| `SessionState`                                                                                         | Observable state shared in the process, plus a persisted record for process death                             |
| `AboutActivity`                                                                                        | About: version, build, license, device; read on the device                                                    |
| `Settings` / `SettingsStore`                                                                           | Validated configuration and its persistence; theme preference                                                 |
| `IntervalScheduler`, `SessionLimits`, `PhotoNames`, `StorageEstimate`, `JpegOrientation`, `Resolution` | Pure Java logic, unit-tested                                                                                  |
| `Texts`, `Ui`, `CenteredIconButton`                                                                    | Shared text formatting, insets and theme helpers; TimerPlus centered icon button                              |

## Threads

- **main:** activities and listeners.
- **camera** (`HandlerThread` in the service): owns all session state and every Camera2 callback.
  Commands from `onStartCommand` are posted here in order.
- **disk** (single executor in the service): MediaStore writes; results are posted back to camera.
- **video** (`HandlerThread` in `VideoService`) and **video-disk**: the same pattern for video
  sessions; finished files are published on video-disk, the last one on video before stopping.
- Activities use their own single executor for camera queries (main-screen context, Settings
  sizes, preview sizes).
- **preview** (`HandlerThread` in `PreviewCamera`, Camera Preview screen only): the live view
  while stopped. The camera is released in the screen's onPause (back, Settings, screen off);
  `close()` reports on the main thread only after the device has closed, and
  `PreviewCamera.whenReleased` lets Start wait for that, so a preview and a session never hold
  the camera at the same time.

## Session lifecycle

1. Start comes only from the visible app (`startForegroundService` → `startForeground` with type
   camera). Settings are loaded once and fixed for the session.
2. The camera is `LensProbe.target`: the same camera id and zoom ratio Camera Preview shows (the ratio is
   set on every request). A lens reached by zoom ratio above 1 is confirmed active on the
   metering stream before the session is ready; if not, it is dropped and the session
   continues on 1× with a warning. The camera opens with two outputs: JPEG `ImageReader`
   (photos) and a small YUV reader for metering only (frames discarded). Intervals up to 10 s keep metering running; longer intervals
   meter only before each shot (max 2 s), then stop.
3. `IntervalScheduler` ticks start-to-start. A tick while a capture or save is in progress, or
   missed because the thread woke late, is counted as skipped.
4. Before each capture: duration limit and free-space reserve. After each save: photo limit.
   Battery and heat: `PowerGuard` (no start at 10 % unplugged or thermal CRITICAL; stop while
   running; warning at SEVERE), the same as for video. The first limit reached ends the session.
5. Camera errors reopen with backoff (1, 2, 4, 8, 16 s); three consecutive capture failures also
   reopen; exhausted retries stop the session with a reason. Permission loss stops at once.
6. Stop releases camera, wake lock and listeners, records the reason, and stops the service with
   the start id of the last handled command.
7. `onDestroy` posts one final step to the camera thread: stop if still active, close the
   camera, shut down the disk executor, then keep the camera looper running (polling every
   20 ms, at most 3 s) until the disk thread has terminated, and only then quit it. A JPEG
   already queued is still saved, and the `onSaved` it produces is still counted and persisted.
   The main thread joins the camera thread for at most 3.5 s.

## Camera stream configuration

One regular session with exactly two outputs: JPEG at the chosen size and YUV_420_888 at the
smallest size at least 320 px wide (well within the PREVIEW bound). This is the guaranteed combination
"YUV PREVIEW + JPEG MAXIMUM", which every hardware level, LEGACY included, must support, **for
JPEG sizes from `getOutputSizes`**. Sizes that appear only in `getHighResolutionOutputSizes` are
offered in Settings but are outside the guaranteed tables; the session-start log marks them
`(high-resolution list)`. If the device rejects the combination on the first configure, the
session stops with a configuration error naming the size; there is no silent fallback. Which
sizes actually configure is confirmed only by a device test.

## Video session (Video mode)

1. Start comes from the visible app, as for photos. The service starts at once (notification,
   wake lock); with a delayed start it waits (status WAITING, countdown in the notification) and
   opens the camera when the delay ends.
2. The camera target is `LensProbe.target`, as for photos. The session has one output, a
   persistent input surface (`MediaCodec.createPersistentInputSurface`) fed to `MediaRecorder`
   (H.264 in MP4, 30 fps nominal, AAC mono when audio is on); with a pending module check, a
   small YUV output is added and nothing is recorded until the check passes.
3. Files: each file is a pending MediaStore entry in `Movies/SentryCam`, named
   `SentryCam_<session UTC>_<hex>_partNNN.mp4`. At the file length (5/10/15 min, or what is left
   of the duration limit) the camera stream pauses, `MediaRecorder.stop()` finalizes the MP4,
   the recorder is prepared on the next file and started, and the stream resumes on the same
   camera session: a short gap between files. A finalized file is published (on the disk thread;
   at the end of a session before the service stops); a file the recorder could not finalize is
   removed. A file reaching 3.8 GB starts the next one early.
4. Every 5 s: duration limit and free-space reserve. Battery and heat: `PowerGuard`, as for
   photos (stop at 10 % unless charging, or at thermal CRITICAL; SEVERE warns). Camera or recorder errors finalize what they can
   and reopen with the photo backoff (1–16 s).
5. Process death loses only the file being recorded (unfinalized MP4 is unplayable); its pending
   entry is removed by the same conservative rule as photos at a later session start.

## Storage

- Insert pending → write → `fsync` → publish; a failed write removes only its own pending entry.
- At session start, abandoned pending entries are removed, only when all hold: pending, our
  folder, our name pattern, owner package is this app, and added at least 10 minutes ago (so a
  write still finishing in a previous service instance is never touched). Anything uncertain is
  kept; MediaStore expires leftover pending entries on its own. Published photos are never
  matched.
- The stored count is one MediaStore query per session, plus an in-memory counter (not shown on
  the main screen).
- Names: `SentryCam_yyyyMMdd_HHmmss_SSS_xxxx.jpg` in UTC; videos
  `SentryCam_<session UTC>_<hex>_partNNN.mp4`.

## State and process death

`SessionState` publishes copies of the session data to listeners on the main thread. A small
SharedPreferences record marks a session as active; if the process dies, the next screen shows
"closed by Android" with the last counts and the mode of that session. Sessions are `START_NOT_STICKY`: the user restarts them.

## UI

Four screens: main (operation: Photo | Video selector, status, two cards, three context rows,
Preview and Start/Stop; toolbar shortcuts to About and Settings), Camera Preview (full-screen
live view, lens chips, gear to Settings), Settings (the current mode's settings; camera, reserve
and theme shared; one screen, opened from main or from Camera Preview) and About. Navigation:
main → Camera Preview → Settings → Camera Preview → main, on the standard back stack.
Styles copied from TimerPlus on framework widgets and
`Theme.Material.Light.NoActionBar` / `Theme.Material.NoActionBar` (night); theme applied per app
with `UiModeManager.setApplicationNightMode`; vector icons; English and Spanish.
