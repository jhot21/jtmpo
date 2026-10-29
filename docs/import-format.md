# Importing setlists

Settings -> Import reads setlist exports from Tempo, another metronome app. This page
describes what the importer accepts and how it maps the data. The code is in
`app/src/main/java/me/jhot/jtmpo/importer/`.

## Files

Both are Apple property-list XML (`<plist version="1.0">`):

| Extension | Root element | Result |
|---|---|---|
| `.fzt` | `<array>` of setlist `<dict>`s | one entry per setlist |
| `.slist` | one setlist `<dict>` | a file with a single setlist |

A setlist dict has `title` (string) and `songs` (array of song dicts). A root `<dict>` with no
`songs` key, any other root element, or anything that isn't a plist is rejected with
"could not be read"; nothing is changed.

## Song fields

| Key | Type | Used for |
|---|---|---|
| `title` | string | song title |
| `tempo` | integer | BPM (clamped to the app's tempo range) |
| `numBeats` | integer | beats per bar |
| `numSubBeats` | integer | `1` = no subdivision, `2` = subdivided |
| `beatCode` | integer | `1` subdivides, `0` doesn't (matters for 6/8 only, see below) |

Every other key is ignored. Some files repeat a key within one song dict (`barTotal`,
`stopAtCountEnd`); the parser builds a DOM rather than a dictionary, so duplicates are harmless.
A song missing any of the five fields above fails the whole file.

## Mapping to a rhythm

`mapFztSong` is the single place this happens, and it always returns a valid rhythm.

| `numBeats` | `numSubBeats` | `beatCode` | Time signature | Click |
|---|---|---|---|---|
| any | 2 | any | `numBeats`/4 | eighth notes |
| any except 6 | 1 | any | `numBeats`/4 | quarter notes |
| 6 | 1 | 1 | 6/8 | eighth notes |
| 6 | 1 | 0 | 6/8 | dotted quarter notes |

In 6/8 the tempo counts dotted quarters per minute in both source groups, which is what jtmpo's
6/8 tempo counts, so the number is copied unchanged.

Accent patterns (`beatStates`) are not imported; jtmpo plays one identical click.

## Merging

Songs are matched on a case-insensitive, trimmed title plus artist (exports have no artist, so
imported songs match library songs that also have none). Importing the same file twice adds no
duplicates.

- **Songs only** adds each distinct title once. If a title appears twice with different rhythms,
  the first copy wins and the difference is reported as a conflict.
- **Songs and a setlist** also creates a setlist in file order. A repeat with a different rhythm
  keeps the library song and records the difference as a per-entry override.

## Safety

Files larger than 5 MB are refused. `<!DOCTYPE>` and `<!ENTITY>` are refused before parsing, and
the XML parser is configured to ignore external entities.

## Opening files from other apps

jtmpo registers three `VIEW` filters on `MainActivity` so a `.jtmpo` can be opened from another app. Each was
checked on the Android 16 emulator, with `adb shell cmd package query-activities` and by opening a file for real:

| Filter | Matches | Seen from |
| --- | --- | --- |
| `application/vnd.jtmpo.setlist+json` | the type jtmpo shares with | `adb shell am start -a android.intent.action.VIEW -t application/vnd.jtmpo.setlist+json -d content://...`; jtmpo is the only candidate |
| `content:` URI whose path ends in `.jtmpo`, any type | a named file under a guessed type such as `application/json` or `text/plain` | `am start` with `-t application/json` and a `.../Name.jtmpo` URI |
| `application/octet-stream` | an unknown binary | the Files app (it opens a `.jtmpo` in Downloads as octet-stream through a `downloads.documents/document/msf:N` URI with no file name); jtmpo shows in "Open with" next to other apps |

All three are kept. The Files app only reaches jtmpo through the octet-stream filter, because its URIs carry no
extension. The extension filter is case-sensitive (`.JTMPO` does not match) and does not match a URI with no type.
None of the filters match `http(s)` URIs, so jtmpo is not offered for web downloads. Opening a file from a real
mail or chat app is still an acceptance step on a phone.

Because the octet-stream filter offers jtmpo for any unknown file, the content decides: a file that is not a
setlist gets a message and changes nothing. A file over 5 MB is refused with "That file is too large to import."
and one that cannot be read with "That file could not be opened."

The file is read into memory as soon as the intent arrives, since the sender's read grant may not last, and the
app then opens Settings -> Import with it. `MainActivity` is `singleTask`: the Files app starts a viewer in its
own task, so with `singleTop` a second jtmpo activity appeared there. With `singleTask` the running instance
gets the file through `onNewIntent`.
