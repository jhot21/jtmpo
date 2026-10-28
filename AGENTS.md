# jtmpo: notes for agents

Android metronome (Kotlin, Jetpack Compose, Material3). Package `me.jhot.jtmpo`, minSdk 26, targetSdk 36.
`README.md` describes the product. `docs/` has the on-device checklist (`acceptance.md`), the click sound (`digital-sound.md`),
the Tempo import (`import-format.md`) and release steps (`releasing.md`).

## Commands

Tools (JDK 21, `ANDROID_HOME`) come from [mise](https://mise.jdx.dev) via `mise.toml`. Non-interactive shells don't activate it, so
prefix everything with `mise exec --` (or set up JDK 21 and `ANDROID_HOME` yourself). The paths below assume the macOS default SDK
location; adjust them for your OS.

```bash
mise exec -- task test      # JVM unit tests (extra args: -- --tests '*RhythmTest')
mise exec -- task check     # lint + unit tests; run before committing
mise exec -- task build     # debug APK
mise exec -- task install   # install the debug APK on the running emulator/device
```

Commits are Conventional Commits (`fix(sheets): ...`). Version numbers live in `gradle.properties`.

## Code layout

`app/src/main/java/me/jhot/jtmpo/`: `audio`, `playback` (foreground-service click engine), `domain`, `data` (Room),
`importer` (Tempo `.slist`/`.fzt`), `backup`, `ui/<screen>` (Compose screens and their ViewModels).
Tests mirror this under `app/src/test`. Gesture and layout behaviour has no unit tests; verify it on the emulator.

## Emulator

The AVD is `Medium_Phone_API_36.1` (arm64, Android 16, 1080x2400). If it is missing its system image, install it once:

```bash
mise exec -- sh -c 'yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null; \
  $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install "system-images;android-36.1;google_apis_playstore;arm64-v8a"'
```

Boot it headless-friendly and wait for `sys.boot_completed`:

```bash
export PATH=$HOME/Library/Android/sdk/platform-tools:$PATH
nohup $HOME/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1 -no-snapshot -no-audio >/dev/null 2>&1 &
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
```

A fresh install is empty. Load the demo library (30 songs, 3 setlists; `demo/README.md`) to reach the Library, the player, and the
edit, queue and add-songs sheets:

```bash
adb push demo/demo-library.json /sdcard/Download/
```

then Settings -> Restore from backup, pick `demo-library.json` in the system picker and confirm. The picker opens on the first tap and
takes a second to draw, so list the screen before tapping the file. Nothing in the demo data overflows a sheet, so it will not
reproduce overflow-only bugs unless you add more songs.

## Driving the app: use mobile-mcp

`.mcp.json` registers `mobile-mcp` (`@mobilenext/mobile-mcp`, pinned, telemetry off). Prefer it over raw `adb` and screenshots:

- `mobile_list_elements_on_screen` returns the Compose UI as elements with text, `label` (contentDescription) and
  bounds, e.g. `View label="Actions for Valerie" at=954,778 size=105x105`. It is the cheapest way to learn the screen
  state; `uiautomator dump` returns nothing useful for Compose. Take a screenshot only when layout or colour matters.
- `mobile_click_on_screen_at_coordinates` takes x,y or an `@eN` ref from the last listing. Device id is the AVD name.
- `mobile_batch_commands` runs several steps in one call. `mobile_get_device_logs` reads logcat.
- Swipes are flings. For a slow held drag (to watch a sheet follow the finger) use
  `adb shell input motionevent DOWN|MOVE|UP x y` (API 31+) and sample state between the moves.

Gotchas that cost time:

- BACK at the app root leaves the app for the launcher, and the next taps then hit the launcher. Dismiss sheets by
  dragging or tapping the scrim; relaunch with `mobile_launch_app` if you land on the launcher.
- Taps sent right after a launch are often lost. List the elements first to confirm the screen is up.
- After a cold start the first database query is slow (seconds on the emulator). Wait for the content, and check it is
  really on screen before a gesture: a drag that starts on the scrim dismisses the sheet.
- Every `adb`/mobile-mcp call is blind to timing. Log state instead of guessing: add a temporary
  `Log.d("TAG", ...)` on a `snapshotFlow` of the value in question, read it with `adb logcat -d | grep TAG`,
  and remove it before committing.

mobile-mcp also ships an optional Claude Code plugin (`/mobile-mirror`) that mirrors the screen in kitty or Ghostty;
it is not needed for the above.

## Bottom sheets (ModalBottomSheet)

Sheet bugs here looked like gesture problems and were layout problems. For a sheet that jitters, snaps back or opens
half off-screen, check these first:

- Never apply window insets inside the sheet through `navigationBarsPadding()` or `contentWindowInsets`. `ModalBottomSheet`
  reports its own drag offset as consumed insets, so the padding shrinks as the sheet moves, resizes the sheet or its
  scroll viewport mid-drag and moves the drag anchors. Read the inset once outside, then pad with that fixed value
  (bottom: `Modifier.padding(bottom = ...)` on the content; top: `Modifier.padding(top = ...)` on the sheet) and pass
  `contentWindowInsets = { WindowInsets(0, 0, 0, 0) }`. `EditSongSheet`, `QueueSheet` and `AddSongsSheet` all follow this.
- Don't show a sheet before its content exists. A sheet that opens empty takes its anchors from the drag handle alone
  and may not re-anchor when content arrives. Compose it only once its ViewModel has loaded.

## README screenshots

`docs/screenshots/` is captured from the emulator with the demo library loaded and Settings at their defaults (flip screen off).
Use `mobile_save_screenshot` for full-resolution PNGs. Retake them when a screen's layout changes.
