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
| [ ] | D1 Import | Copy a `.slist` export to Downloads on the phone. Settings -> Import a setlist, Choose file, pick it; its one setlist is already selected. Under "Import as" pick "Songs and a setlist" (the default is Songs only). Review the conflict preview, confirm. Repeat once with a full `.fzt` export and choose a single setlist. | Only that setlist and its songs arrive; tempos, time signatures and click patterns look right; the picker works on the device. | |
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
| [ ] | F1 Share | Open a setlist's menu (both on the setlists list and inside the setlist) and tap Share. Pick Files/Drive or another app. | Share is in both menus. The share sheet offers a file named after the setlist ending `.jtmpo`. Share is disabled, in both menus, for an empty setlist. | |
| [ ] | F2 Open from Files | `adb push demo/demo-setlist.jtmpo /sdcard/Download/`. In the Files app open it from Downloads and choose jtmpo. | jtmpo comes to the front (no second copy of the app in recents) on Settings -> Import with a confirmation titled "Import "Sunday Jam"?". | |
| [ ] | F3 Import via Settings | With the file in Downloads, Settings -> Import, Choose file, pick it. | Same confirmation as F2. A Tempo `.slist` still imports as before (D1); a backup file shows "This looks like a backup. Use Restore."; any other file shows "Not a jtmpo setlist file." | |
| [ ] | F4 Dialog B | Continue F3 with the demo library loaded. Confirm. | "Conflict 1 of 1": the file's "Layla" has no artist, yours is by Derek and the Dominos (with the library as it comes, this is the only conflict). Only Use my song and Add as a new song are offered. | |
| [ ] | F5 Result | Choose Use my song, finish. Open the new setlist "Sunday Jam". | 4 songs in order: Hotel California, Paper Lanterns, Sweet Home Alabama, Layla. Paper Lanterns is new in the Library. Sweet Home Alabama plays at 104 BPM, marked **set**, library copy still 98. The others have no override. Setlists shows no last-played time for it. | |
| [ ] | F6 Dialog A | The demo library has no artist-less songs, so restore it fresh (D3), then create "Paper Lanterns" with no artist by hand in the Library. Import the demo setlist. (Without that hand-made song the fixture has exactly one conflict, Layla, as in F4.) | Conflicts are asked in file order, so "Conflict 1 of 2" is for Paper Lanterns: your library has it with no artist, the file's is by The Inventions. Update my song, Use my song and Add as a new song are offered. Update changes the library song to the file's artist, tempo and notes in every setlist; "Conflict 2 of 2" is the Layla dialog from F4. | |
| [ ] | F7 Cancel mid-sequence | Import the file with the A and B conflicts both pending (as in F6, with the hand-made Paper Lanterns back in place). Answer the first, then tap Cancel import on the second. | Back on Import with nothing changed: no new setlist, no new or updated songs. | |
| [ ] | F8 Name collision | Import the file twice. | The second confirmation says you already have "Sunday Jam" and offers Add as "Sunday Jam (2)". The first setlist is untouched; songs are reused, not duplicated. | |
| [ ] | F9 Text message (owner) | Send a setlist's `.jtmpo` to the phone by text message (MMS or RCS). Open the attachment. | jtmpo is offered and opens the confirmation, or note which app refuses and why. | |
| [ ] | F10 Email (owner) | Email the `.jtmpo` as an attachment to the phone. Open it from the mail app. | jtmpo is offered and opens the confirmation, or note which app refuses and why. | |

## G. Practice

The Practice tab is a metronome on its own: no song or setlist needed. G11 uses the demo library
(`demo/README.md`); the timing items are best checked with in-ears against a second reference
metronome.

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | G1 Ramp up on the bar line | Practice: Ramp Up, Step 5, Bars before step 4, tempo 60. Play for two minutes. | The click speeds up by 5 BPM on the first click of every fourth bar, with no gap, double click or stutter at the change. The tempo card and "Bar N of 4 · next X" follow the click, and the bar count restarts at 1 as each step lands. | |
| [ ] | G2 Ramp down | Same with Down from 200 BPM. | The click slows by 5 BPM every fourth bar, on the bar line. The single gap right after a step down may still be at the old tempo; at these step sizes it should be inaudible. | |
| [ ] | G3 100 and 200 BPM | Repeat G1 with tempo 100, then 200. | Same clean steps on the bar line at both tempos; the card stays in step with the click. | |
| [ ] | G4 Upper limit | Up, Step 50, Bars before step 1, tempo 300: play. Then set 4/4 Click 1/16 (cap 150 BPM) and 6/8 Click 1/8 (cap 200 BPM), start just below each cap and ramp up. | The click keeps going at the limit and the line reads "at limit" instead of "next"; the tempo never climbs past 300 or the subdivision's cap. | |
| [ ] | G5 Pause and resume | With a ramp running, pause mid-interval, wait 10 seconds, play again. | The click stops and the tempo and bar count freeze while paused. Play restarts at the top of the interrupted bar with the count kept, and the next step still lands on a bar line. | |
| [ ] | G6 Reset | Reset is hidden until the ramp has stepped the tempo off the start tempo, and while the ramp is Off. Once it has stepped: tap it once while playing and once while paused. | Tempo returns to the start tempo and the bar count to 1. While playing the click restarts at once at the start tempo on bar 1 (no count-in); while paused nothing sounds until play, which then starts at bar 1. | |
| [ ] | G7 Nudge mid-ramp | With Up running, drag the tempo control and use its -/+ mid-session. | The live tempo moves to the new value and the ramp carries on from there, still stepping on the bar line. The start tempo stays as it was until Reset, which returns to it. Before the first step (even after playing or pausing), after Reset, and with the ramp Off, the tempo control moves the start tempo too and Reset stays hidden. Switching the ramp to Off makes the shown tempo the start tempo. | |
| [ ] | G8 Rotate | Tap the rotate button in the Practice header, then force-stop and relaunch. | Practice flips 180° and back, and stays in the state it was left in after the relaunch. Other tabs are unaffected. | |
| [ ] | G9 Screen off | Settings: turn Keep screen awake off. Ramp Up from 60. Play, switch the screen off, leave 5 minutes. | The screen times out normally and the click keeps going, still stepping on the bar line. Waking the phone shows the tempo the ramp has reached. | |
| [ ] | G10 Unplug headphones | Play Practice with wired in-ears; unplug the adapter. | The click pauses at once and nothing plays from the speaker; replugging stays paused until play. | |
| [ ] | G11 A song takes over | With Practice playing, open a setlist and start a song. Then return to Practice and play. | Practice stops when the song starts, the notification switches to the song, and the setlist is untouched by Practice. Playing in Practice stops the song and resumes the click at the tempo and bar it was paused at. | |
| [ ] | G12 Back and Settings | From Practice press Back. Reopen jtmpo, open Settings from Practice, then press Back. | Back from Practice leaves the app, with no tab switch. Back from Settings returns to Practice, not to Setlists. | |

## H. Player queue sheet

The setlist at the bottom of the player is a non-modal sheet: hidden (the "Up next" bar), half (five rows) or full.
Use the demo library (Friday Night Set, 10 songs) and try each item at the default font size and at 1.3x.

| Done | Item | Steps | Expected | Result |
| --- | --- | --- | --- | --- |
| [ ] | H1 Half stop | In the player tap the "Up next" bar. | The sheet opens to exactly five song rows (no half row, no gap). The player shrinks above it: artist and notes hide, the tempo card grows to fill the free height (no empty gap above previous/play/next), previous/play/next stay visible. | |
| [ ] | H1b Follows the finger | Drag the "Up next" bar up slowly, then back down; do the same from half with the handle or header. | The sheet and the player's layout follow the finger. A small movement (a thumb-width or less) settles back where it was; about 100 dp opens or closes it; a quick flick goes to the next stop. | |
| [ ] | H2 Player stays usable | At half, tap play/pause, previous/next, the -/+ buttons and drag the tempo track. | All of them work with the sheet still open; the drag bubble floats over the BPM label. | |
| [ ] | H3 Scroll at half | At half, swipe the song list up and down. | The list scrolls; the sheet stays at half. | |
| [ ] | H4 Playing song visible | Play song 8 (or press next to get there), then open the sheet. Press previous and next. | Past the fifth row the playing song is the last visible row; previous/next keep it on screen; tapping a row that is already visible does not move the list. | |
| [ ] | H5 Full and back | Drag the sheet handle (or the header) up, then down. | Up goes to full height and the full list scrolls; down returns to half, and again to hidden (the "Up next" bar). No strip of the sheet shows at the bottom edge while hidden. | |
| [ ] | H6 Reorder | At half and at full, drag a row by its handle. | The row moves and the new order is kept; it does not fight with scrolling or the sheet. | |
| [ ] | H7 Edit and add | From the sheet tap a row's pencil, then "Add from library". | The edit sheet and the add sheet open over the queue as before; closing them leaves the queue where it was. | |
| [ ] | H8 Back and unsaved change | Make an unsaved tempo change, open the sheet, then press Back. | Back hides the sheet first, and a second Back leaves the player. The unsaved bar is visible at half (at 1.3x scroll the player to reach the save buttons). | |
| [ ] | H9 Short setlist | Open a setlist with fewer than five songs (add one if needed) and open the sheet. | The sheet is only as tall as its songs, no empty space. | |

## Sign-off

Date: ____  Build/commit: ____  Overall: ____
Notes:
