# Demo library

`demo-library.json` is a jtmpo backup holding 30 classic-rock songs (tempos are approximate) and
three setlists, for trying the app and taking screenshots. Load it on a device or emulator:

```bash
adb push demo/demo-library.json /sdcard/Download/
```

then Settings -> Restore from backup and pick the file. Restoring replaces everything in the app.

## Demo setlist

`demo-setlist.jtmpo` is a shared setlist, "Sunday Jam", for trying the `.jtmpo` import against the demo
library (load the library first). Push it and import it:

```bash
adb push demo/demo-setlist.jtmpo /sdcard/Download/
```

then Settings -> Import, Choose file and pick it (or open it from the Files app). Its four songs show
each import outcome:

| Song | Outcome |
|---|---|
| Hotel California (Eagles) | exact match, reused as is |
| Paper Lanterns (The Inventions) | new song, added to the library |
| Sweet Home Alabama (Lynyrd Skynyrd) | exact match, played at 104 BPM instead of 98, so the entry gets a tempo override |
| Layla (no artist) | matches the library's Layla by Derek and the Dominos by title only: one "Conflict 1 of 1" dialog |

The demo library has no artist-less songs, so the dialog where your song has no artist cannot be reached
from it; see `docs/acceptance.md` (F6). `DemoSetlistTest` keeps this table true.
