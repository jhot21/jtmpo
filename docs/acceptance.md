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

## F. Sharing a setlist

Uses the demo library (`demo/README.md`) and `demo/demo-setlist.jtmpo`. Steps that need a real phone with
messaging or mail are for the owner; a build agent cannot run them.

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | F1 Share | Open a setlist's menu (both on the setlists list and inside the setlist) and tap Share. Pick Files/Drive or another app. | Share is in both menus. The share sheet offers a file named after the setlist ending `.jtmpo`. An empty setlist has nothing to share (Share is disabled inside it). | |
| [ ] | F2 Open from Files | `adb push demo/demo-setlist.jtmpo /sdcard/Download/`. In the Files app open it from Downloads and choose jtmpo. | jtmpo comes to the front (no second copy of the app in recents) on Settings -> Import with a confirmation titled "Import "Sunday Jam"?". | |
| [ ] | F3 Import via Settings | With the file in Downloads, Settings -> Import, Choose file, pick it. | Same confirmation as F2. A Tempo `.slist` still imports as before (D1); a backup file shows "This looks like a backup. Use Restore."; any other file shows "Not a jtmpo setlist file." | |
| [ ] | F4 Dialog B | Continue F3 with the demo library loaded. Confirm. | "Conflict 1 of 1": the file's "Layla" has no artist, yours is by Derek and the Dominos (with the library as it comes, this is the only conflict). Only Use my song and Add as a new song are offered. | |
| [ ] | F5 Result | Choose Use my song, finish. Open the new setlist "Sunday Jam". | 4 songs in order: Hotel California, Paper Lanterns, Sweet Home Alabama, Layla. Paper Lanterns is new in the Library. Sweet Home Alabama plays at 104 BPM, marked **set**, library copy still 98. The others have no override. Setlists shows no last-played time for it. | |
| [ ] | F6 Dialog A | The demo library has no artist-less songs, so restore it fresh (D3), then create "Paper Lanterns" with no artist by hand in the Library. Import the demo setlist. | "Conflict 1 of 2" for Paper Lanterns: your library has it with no artist, the file's is by The Inventions. Update my song, Use my song and Add as a new song are offered. Update changes the library song to the file's artist, tempo and notes in every setlist; "Conflict 2 of 2" is the Layla dialog from F4. | |
| [ ] | F7 Cancel mid-sequence | Import the file with the A and B conflicts both pending (as in F6, with the hand-made Paper Lanterns back in place). Answer the first, then tap Cancel import on the second. | Back on Import with nothing changed: no new setlist, no new or updated songs. | |
| [ ] | F8 Name collision | Import the file twice. | The second confirmation says you already have "Sunday Jam" and offers Add as "Sunday Jam (2)". The first setlist is untouched; songs are reused, not duplicated. | |
| [ ] | F9 Text message (owner) | Send a setlist's `.jtmpo` to the phone by text message (MMS or RCS). Open the attachment. | jtmpo is offered and opens the confirmation, or note which app refuses and why. | |
| [ ] | F10 Email (owner) | Email the `.jtmpo` as an attachment to the phone. Open it from the mail app. | jtmpo is offered and opens the confirmation, or note which app refuses and why. | |

## Sign-off

Date: ____  Build/commit: ____  Overall: ____
Notes:
