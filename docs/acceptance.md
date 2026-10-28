# jtmpo manual test checklist

What to check on a physical device before a release; the unit tests cover logic, not audio
routing, timing or touch feel. Tick the box and note the outcome in Result. Use real in-ears
(wired through a USB-C adapter, then Bluetooth where noted).

Setup: `mise exec -- task install` with the phone attached, set media volume to a comfortable
level, and have a second reference metronome on another device for the drift check.

## A. Highest risk: interruptions and routing

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | A1 Incoming phone call during playback | Play a song at ~120 BPM, once on wired in-ears and once on Bluetooth earbuds. Call the phone from another phone. Answer it, talk, hang up. | Click keeps going through ring and call (does not pause or duck), including when the call moves audio to the earpiece or switches the earbuds from media to call mode. It pauses only if the call moves the click to the phone speaker. Record separately: does the call audio reach the in-ears, and does the click stay audible over it? |  |
| [ ] | A2 Unplug USB-C adapter | Play with the wired in-ears. Unplug the adapter. | Click pauses at once; nothing plays from the phone speaker. Replug: it stays paused until you press play. |  |
| [ ] | A3 Bluetooth disconnect | Play over Bluetooth earbuds. Switch them off / move out of range. | Click pauses; nothing from the speaker. |  |
| [ ] | A4 Notification while playing | Play, then send yourself a message/notification (sound on). | Click does not pause and does not duck. |  |
| [ ] | A5 Swipe from recents | Play, press recents, swipe jtmpo away. | Click keeps going; media notification still present. Reopen the app: state matches. |  |
| [ ] | A6 Unrelated Bluetooth disconnect | Pair a Bluetooth speaker or earbuds and leave them connected but idle. Play through the wired in-ears (route on the wired adapter). Switch the Bluetooth device off; switch it back on and let it connect. | An unrelated Bluetooth device disconnects while playing through wired in-ears: click keeps playing. If the reconnecting device takes the route away from the in-ears (outside a call), the click pauses rather than moving to it. |  |

Route rules (A1-A6): the click pauses only when its own output goes away (unplug, Bluetooth off,
"becoming noisy"), when the route moves to the phone speaker, or when a different external device
takes the route outside a call. A move to the earpiece or a call-mode switch of the same headset
never pauses. If any A item pauses unexpectedly (or right after pressing Play), run
`adb logcat -d -s jtmpo.audio` and note the `route at start:` and `route changed:` lines (device
type/id, baseline, pause=true/false) in Result.

## B. Background and steadiness

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | B1 Screen off 10 minutes | Play at 120 BPM, switch the screen off, leave 10 min next to a reference metronome at the same tempo. | Steady, no audible drift or stutter against the reference. |  |
| [ ] | B2 AudioTrack underruns | Before B1 run `adb logcat -c`. After B1, pause the click and run `adb logcat -d -s jtmpo.audio`. | The `stopped: underruns=N bufferSizeInFrames=M` line logged when the click stopped shows `underruns=0`. If non-zero: raise the AudioTrack buffer to 2-4x min in `app/src/main/java/me/jhot/jtmpo/audio/AudioTrackOutput.kt`, rebuild, repeat B1. |  |
| [ ] | B3 Lock-screen and notification controls | Play, lock the phone. Use play/pause on the lock screen and in the notification shade. | Each toggles the click immediately and the in-app state follows. |  |
| [ ] | B4 Keep-awake | Settings: enable keep-awake. Play with the screen untouched past the normal timeout. Then disable it and repeat. | On: screen stays awake while playing. Off: screen times out normally; click still plays. |  |
| [ ] | B5 Pulse alignment | Play at a slow tempo (60 BPM) with the pulse indicator in view, in-ears in. | Visual pulse lands with the audible click; no obvious lead or lag. |  |

## C. Feel and tuning

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | C1 Tempo drag on the player | Drag on the tempo control with a real thumb: slow nudges, then fast flicks. | Slow drags change by about 1 BPM steps; fast ones accelerate but do not jump absurdly. (On the emulator a 600 px swipe in 150 ms hit 300 BPM, which is probably too hot.) If so, lower `MAX_FACTOR` and/or `GAIN_PER_PX_PER_MS` in `TempoDragTuning`. |  |
| [ ] | C2 Tempo drag in the edit sheet | Same drag in the song edit sheet. | Same feel; haptics, if present, feel right. |  |
| [ ] | C3 6/8 speed | Import a 6/8 song with eighth-note clicks and one with dotted-quarter clicks (D1). Play each next to a reference metronome at the same displayed tempo. | Perceived speed matches: the tempo counts dotted quarters in both. | |

## D. Import, backup, and flow

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | D1 Import | Copy a `.slist` export to Downloads on the phone. Settings -> Import from Tempo (.slist / .fzt), Choose file, pick it; its one setlist is already selected. Under "Import as" pick "Songs and a setlist" (the default is Songs only). Review the conflict preview, confirm. Repeat once with a full `.fzt` export and choose a single setlist. | Only that setlist and its songs arrive; tempos, time signatures and click patterns look right; the picker works on the device. | |
| [ ] | D2 Backup export via Drive | Settings -> Export backup, save to Google Drive through the system picker. | File appears in Drive; success message shown. |  |
| [ ] | D3 Restore via Drive | Change or delete something, then Settings -> Restore from backup, pick the Drive file. | Data returns to the backed-up state. |  |
| [ ] | D4 Pause after restore | Play a song, then run D3. | Click stops audibly when the restore happens and stays stopped; nothing keeps clicking on stale data. |  |
| [ ] | D5 Play on an unloaded setlist | (a) With the click paused and setlist X not loaded, tap Play on X / tap one of its rows. (b) While setlist Y is clicking, open X and tap Play / tap one of its rows. | (a) X loads paused and the player opens; you must press play (no surprise click in the in-ears). (b) The click never goes silent: it switches straight to X's song at its tempo and keeps playing. | |

## E. Phone setup for performing (not an app feature)

jtmpo does not manage system volume or call blocking. Use a Do Not Disturb schedule (or an
automation app) that, while performing:

- turns do-not-disturb on with calls blocked, and
- sets media volume to max (or your known-good level).

Verify once: with it active, repeat A1 and A4 and note what changes.

| Done | Item | Result |
| --- | --- | --- |
| [ ] | E1 Tasker/DND profile created and tested |  |

## Sign-off

Date: ____  Build/commit: ____  Overall: ____
Notes:
