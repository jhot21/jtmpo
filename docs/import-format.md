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
