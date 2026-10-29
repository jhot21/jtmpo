# Importing setlists

Settings -> Import reads setlist exports from Tempo, another metronome app, and setlists shared from
jtmpo itself (`.jtmpo`). This page describes what the importer accepts and how it maps the data. The
Tempo code is in `app/src/main/java/me/jhot/jtmpo/importer/`, the `.jtmpo` code in `.../share/`.
Jump to [Sharing a setlist (.jtmpo)](#sharing-a-setlist-jtmpo) for the jtmpo format.

## Which format a file is

The file's content decides, never its name or the type another app reports (`detectFormat`):

| Content | Treated as |
|---|---|
| XML containing `<plist` | a Tempo export (everything in "Files" to "Safety" below) |
| JSON with `"type": "jtmpo-setlist"` | a `.jtmpo` setlist |
| JSON with a `setlists` array and no `type` | a backup: refused with "This looks like a backup. Use Restore." |
| anything else, or over 5 MB | refused with "Not a jtmpo setlist file." |

A refused file changes nothing.

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

## Sharing a setlist (.jtmpo)

Share is in both setlist menus (the list of setlists and the open setlist). It writes the setlist as a
`.jtmpo` file named after it and opens the Android share sheet; the recipient opens the file, or picks
it in Settings -> Import. Share is disabled for an empty setlist, in both menus. A `.jtmpo` is one JSON document:

```json
{
  "type": "jtmpo-setlist",
  "version": 1,
  "name": "Sunday Jam",
  "songs": [
    {"id": 1, "title": "Paper Lanterns", "artist": "The Inventions", "tempo": 126,
     "tsNum": 4, "tsDen": 4, "patDen": 4, "patDotted": false, "notes": "Count in two bars."}
  ],
  "entries": [
    {"songId": 1, "tempo": 120, "tsNum": 4, "tsDen": 4, "patDen": 4, "patDotted": false}
  ]
}
```

`songs` lists each distinct song once with its library values; `id` is only a label inside the file.
`entries` is the setlist in order and carries the values the sender plays (so a per-setlist override
travels as a difference between an entry and its song). `artist` and `notes` may be absent. A newer
`version` is refused ("from a newer version of jtmpo"); a file with no entries, a blank title, duplicate
songs or an entry that names a missing song is refused as unreadable. Rhythm values are sanitised the same
way as a restore.

A `.jtmpo` is also bounded in content, since the 5 MB cap alone does not limit how many objects it decodes to: at most
2000 songs, 5000 entries, 500 characters for a title, an artist or the setlist name (after trimming) and 10000 characters
for each song or entry note. A file over any limit is refused as unreadable with a message naming the limit. Realistic
setlists are far below the limits, but the app does not enforce them when editing or sharing, so an over-limit setlist
exports fine and is then refused on import with the "could not be read" message.

Files written for sharing live in the app's cache; ones at least a day old are deleted when the app starts, so a file a
chat app has not read yet is not pulled away.

A v1-era backup that holds songs differing only in case or whitespace is refused on Restore with its own message, while
the in-place upgrade to the new database merges the same data.

### How songs are matched

Songs match on the same key as the rest of the app: trimmed, case-insensitive title plus artist, with a
blank artist counting as none. Exact matches are claimed first across the whole file, then the rest:

| File song | Library has | Result |
|---|---|---|
| title + artist | same title + artist | **Exact**: the library song is reused, nothing is asked |
| title + artist | same title, no artist | **Conflict A** |
| title, no artist | same title, with an artist | **Conflict B** |
| anything else | nothing matching | **New**: added to the library |

A library song is claimed by one file song at most, so two file songs never fight over it.

### Conflict dialogs

Each conflict is asked in turn ("Conflict 1 of 2"), before anything is written.

- **A** (your song has no artist, the file's has): **Update my song** (replaces its artist, tempo, time
  signature, click pattern and notes with the file's, in every setlist that uses it), **Use my song**, or
  **Add as a new song**.
- **B** (the file's song has no artist, yours does): **Use my song** or **Add as a new song**. There is no
  update, because it would erase your artist.

The dialogs also list how the file differs (tempo, time signature, click pattern, notes). Cancelling at any
point discards the import; nothing is written until the last answer, and then everything is written in one
transaction.

### Overrides

Each entry is compared with the library song as it will be after the import. A different rhythm (any of
tempo, time signature or click pattern) stores the entry's whole rhythm as a per-setlist override. Notes
override only when the file's notes are non-blank and differ; blank file notes never hide your own.

### Setlist name

The setlist keeps the file's name, or "Shared setlist" if it has none. If you already have one with that
name (trimmed, ignoring case), the confirmation offers **Add as "Name (2)"**, then "(3)" and so on; the
existing setlist is untouched.

### Importing again, and play history

Importing the same file again reuses the songs (they are exact matches now) and adds a second setlist,
named "Name (2)". A `.jtmpo` never sets or changes `lastPlayedAt`: the new setlist has no last-played time.

## Opening files from other apps

jtmpo registers three `VIEW` filters on `MainActivity` so a `.jtmpo` can be opened from another app. Each was
checked on the Android 16 emulator, with `adb shell cmd package query-activities` and by opening a file for real:

| Filter | Matches | Seen from |
| --- | --- | --- |
| `application/vnd.jtmpo.setlist+json` | the type jtmpo shares with | `adb shell am start -a android.intent.action.VIEW -t application/vnd.jtmpo.setlist+json -d content://...`; jtmpo is the only candidate |
| `content:` URI whose path ends in `.jtmpo`, any type | a named file under a guessed type such as `application/json` or `text/plain` | `am start` with `-t application/json` and a `.../Name.jtmpo` URI |
| `application/octet-stream` | an unknown binary | the Files app (it opens a `.jtmpo` in Downloads as octet-stream through a `downloads.documents/document/msf:N` URI with no file name); jtmpo shows in "Open with" next to other apps |

The two filters that name only a type also declare `scheme="content"`; without it Android matches `file://` URIs
too. Only `content://` URIs reach jtmpo, and a `file://` URI is not handled (checked with `cmd package
resolve-activity` / `query-activities`).

All three are kept. The Files app only reaches jtmpo through the octet-stream filter, because its URIs carry no
extension. The extension filter is case-sensitive (`.JTMPO` does not match) and does not match a URI with no type.
None of the filters match `http(s)` or `file` URIs, so jtmpo is not offered for web downloads. Opening a file from a real
mail or chat app is still an acceptance step on a phone (`docs/acceptance.md`, F9 and F10).

Because the octet-stream filter offers jtmpo for any unknown file, the content decides: a file that is not a
setlist gets a message and changes nothing. A file over 5 MB is refused with "That file is too large to import."
and one that cannot be read with "That file could not be opened."

The file is read into memory as soon as the intent arrives, since the sender's read grant may not last, and the
app then opens Settings -> Import with it. `MainActivity` is `singleTask`: the Files app starts a viewer in its
own task, so with `singleTop` a second jtmpo activity appeared there. With `singleTask` the running instance
gets the file through `onNewIntent`.
