# Demo library

`demo-library.json` is a jtmpo backup holding 30 classic-rock songs (tempos are approximate) and
three setlists, for trying the app and taking screenshots. Load it on a device or emulator:

```bash
adb push demo/demo-library.json /sdcard/Download/
```

then Settings -> Restore from backup and pick the file. Restoring replaces everything in the app.
