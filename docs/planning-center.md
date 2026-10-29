# Exporting a Planning Center plan

`scripts/pco-slist.user.js` is a userscript that adds a "Download .jtmpo" button to Planning Center
Services plan pages. The file it downloads is a jtmpo setlist (see "The .jtmpo format" in
`import-format.md`). Open it from a file manager, or pick it in Settings -> Import.

## Install

1. Install a userscript manager such as Violentmonkey or Tampermonkey.
2. Create a new script and paste in `scripts/pco-slist.user.js`.
3. Open a plan at `services.planningcenteronline.com/plans/<id>` while logged in and click the button.

The script reads the plan with your logged-in browser session, so there is no token to create or
store. If Planning Center refuses the request (401/403), the button reports it. The setlist is named
after the plan's date, and the file after the same.

## Mapping

Each song item in the plan uses its arrangement's BPM and meter, and the song's author.

| Planning Center | Result |
|---|---|
| title | the song title, trimmed |
| song author | the artist (set `USE_AUTHOR_AS_ARTIST` to `false` at the top of the script for none) |
| `n/4`, `n/2`, simple `n/8` or `n/16` | `n` beats, one click per beat, BPM unchanged |
| `6/8`, `9/8`, `12/8` (and the same over 16) | compound: dotted-beat clicks, BPM divided by 3 (Planning Center counts the eighths, jtmpo counts the dotted beats) |
| key chosen in the plan | the entry's note, `Key: E`, so it travels with this set and not with the library song |
| blank meter | assumed 4/4, and listed as a warning |
| no BPM, no title, or a meter jtmpo can't represent | song skipped, and listed as a warning |

A song used twice in one plan becomes one library song with two entries, each keeping the tempo and
key it plays with. Assumed and skipped songs are listed after the download so you can check them.

## Import behaviour to expect

The author is a songwriter list ("A, B, and C"), not a performing artist, and Tempo imports have no
artist at all. A song you already have without an artist therefore shows the "Conflict" dialog
(update my song, use my song, or add as a new song) the first time. Turn `USE_AUTHOR_AS_ARTIST` off
to avoid that.

## Tests

```bash
mise exec -- task test:scripts
```
