# The click

The click is synthesised, not recorded. `tools/synth_click.py` generates both files in
`sounds/`, and `app/src/main/res/raw/digital_tock_48k.wav` is a copy of the 48 kHz one
(a unit test keeps the two identical).

```bash
./tools/synth_click.py sounds/                    # regenerate both rates
cp sounds/digital-tock-48k.wav app/src/main/res/raw/digital_tock_48k.wav
```

Nothing in it is random, so the output is reproducible. The parameters are constants at the
top of the script.

## What it is

| Property | Value |
|---|---|
| Format | PCM WAV, 16-bit signed, mono, little-endian |
| Sample rates | 48000 and 44100 (same sound rendered for each) |
| Length | 240 ms exactly (11520 / 10584 samples) |
| Fundamental | 1401 Hz |
| Peak | 1.000, normalised to full scale |
| RMS | about 0.15 |
| Attack | raised-cosine rise over 2.8 ms, peak at about 3 ms |
| Decay | exponential, tau 16.5 ms, forced to zero at 55 ms |
| Tail | digital silence from 55 ms to 240 ms |

It is a 1.4 kHz sine with a soft attack, plus five overtones (2nd, 3rd, 5th, 6th, 7th
harmonic) and a short ~2 kHz burst. The overtones and the burst fade within about 15 ms and are
what make the onset bright rather than a dull beep. The 3rd harmonic, at 4.2 kHz, dominates.

The 240 ms length is deliberate: it is just inside the beat interval at 250 BPM, so the note
is never cut off at any supported tempo.

## Using it

- **Pick the rate that matches the output stream.** Use the 48 kHz file on a 48 kHz
  `AudioTrack`/AAudio stream and the 44.1 kHz file on a 44.1 kHz one. Feeding 44.1 k samples
  to a 48 k stream makes Android resample, which adds a little latency and jitter.
- **Keep the lead-in.** The peak is about 3 ms in. Trimming it would shift every click
  relative to the beat grid.
- **It is a one-shot.** Trigger it per beat; don't loop it.
- **One sample covers a whole pattern.** Build accents with gain rather than extra files.
- **Decode once** into a `short[]` at startup (23 KB, 0.24 s), not per beat.

## How the parameters were chosen

They were fitted to measurements of a click recorded from another metronome app, so the sound
is a close match rather than a copy. The measurements that drove the fit, and how close the
result is, are in `./tools/synth_click.py --compare <reference.wav>`:

- The envelope tracks the reference to within about 1 dB (mean) from the first millisecond to
  40 ms, and is a few dB loud in the last 10 ms of the tail, where it is below -40 dB.
- Energy in each band (1-1.8, 1.8-3.5, 3.5-6, 6-12 kHz) is within 2 dB of the reference. The
  synth has about 10 dB less content below 1 kHz, at about -45 dB re total.

Two things learned along the way, in case it is tuned again:

- **Compare total energy per band, not the loudest bin.** The first version matched on the
  strongest bin in each band and looked right while sounding duller, because the reference's
  extra brightness sits in the onset, not in one peak.
- **Don't use noise for the onset.** A seeded noise burst matched the band energy but sounded
  harsh, with a few sharp peaks. The brightness is harmonic, not noisy; modelling it as
  harmonics with their own fast envelopes fixed it.

To retune, edit the constants in `tools/synth_click.py`, compare, and listen. Raising `FREQ`
makes it cut through a loud room, and lowering it makes it duller.
