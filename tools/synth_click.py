#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy"]
# ///
"""Synthesise the metronome click from scratch, and compare it against a reference WAV.

    ./tools/synth_click.py out/                         # writes digital-tock-{44k,48k}.wav
    ./tools/synth_click.py out/ --compare ref-48k.wav   # also prints how close it is

The click is a ~1.4 kHz sine with a soft attack and a ~55 ms tail, plus a few overtones and a
short burst that fade within the first ~15 ms and give the onset its brightness. It is padded
with silence to 240 ms (just inside the beat interval at 250 BPM, so it is never cut off). The
parameters were fitted to measurements of an existing click and nothing in it is random.
"""
import argparse
import sys
import wave
from pathlib import Path

import numpy as np

FREQ = 1401.0  # Hz
PHASE = 2.99  # rad; irrelevant to the ear, kept so the onset matches the reference
ATTACK = 0.0028  # s, raised-cosine rise to full level
TAU = 0.0165  # s, exponential decay constant
TAIL = 0.055  # s, the envelope is forced to zero here
TAIL_POWER = 1.65  # shape of the fade into TAIL; 1 is linear
# Overtones, each with its own rise and decay (they are not scaled by the fundamental's envelope):
# (harmonic number, level re the fundamental, rise s, decay tau s, phase rad). These make the
# onset bright; the 3rd, at 4.2 kHz, dominates.
HARMONICS = (
    (2, 0.0350, 0.00238, 0.00671, 1.232),
    (3, 0.2700, 0.00333, 0.00474, -0.136),
    (5, 0.1216, 0.00456, 0.00220, 0.518),
    (6, 0.0169, 0.00554, 0.00174, 2.373),
    (7, 0.0440, 0.00501, 0.00163, 1.649),
)
# A short tone burst near 2 kHz in the first 2 ms: (level, Hz, centre s, width s, phase rad).
BURST = (0.1444, 1951.5, 0.00125, 0.00158, -2.786)
LENGTH = 0.240  # s, total file length
GAIN = 1.0  # peak, as a fraction of full scale
RATES = {"48k": 48000, "44k": 44100}


def synth(rate: int) -> np.ndarray:
    n = round(LENGTH * rate)
    t = np.arange(n) / rate
    attack = np.sin(np.pi / 2 * np.clip(t / ATTACK, 0, 1)) ** 2
    tail = np.clip(1 - (t / TAIL) ** TAIL_POWER, 0, None)
    th = 2 * np.pi * FREQ * t + PHASE
    x = attack * np.exp(-t / TAU) * tail * np.sin(th)
    for n, level, rise, tau, phase in HARMONICS:
        grow = np.sin(np.pi / 2 * np.clip(t / rise, 0, 1)) ** 2
        x += level * grow * np.exp(-t / tau) * np.sin(n * th + phase)
    level, freq, centre, width, phase = BURST
    x += level * np.exp(-(((t - centre) / width) ** 2)) * np.sin(2 * np.pi * freq * t + phase)
    return x * (GAIN / np.abs(x).max())


def write_wav(path: Path, x: np.ndarray, rate: int) -> None:
    pcm = np.round(np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path)) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, "expected 16-bit mono"
        rate = w.getframerate()
        return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(float) / 32768, rate


def peak_env(x: np.ndarray, rate: int) -> np.ndarray:
    """Running max of |x| over about two cycles of the fundamental."""
    win = int(rate / FREQ * 2)
    pad = np.pad(np.abs(x), (win // 2, win))
    return np.array([pad[i : i + win].max() for i in range(len(x))])


def fundamental(x: np.ndarray, rate: int) -> float:
    seg = x[: int(0.06 * rate)]
    n = len(seg) * 16
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), n))
    return float(np.argmax(spec) * rate / n)


def compare(a: np.ndarray, b: np.ndarray, rate: int) -> None:
    """a is the synthesised click, b the reference."""
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    rms = lambda v: float(np.sqrt((v**2).mean()))  # noqa: E731
    print(f"{'':22}{'synth':>10}{'reference':>12}")
    print(f"{'peak':22}{np.abs(a).max():10.3f}{np.abs(b).max():12.3f}")
    print(f"{'RMS':22}{rms(a):10.4f}{rms(b):12.4f}")
    print(f"{'fundamental (Hz)':22}{fundamental(a, rate):10.1f}{fundamental(b, rate):12.1f}")

    ea, eb = peak_env(a, rate), peak_env(b, rate)
    print("\nenvelope (dB re peak)     synth   reference    diff")
    diffs = []
    for ms in (1, 2, 3, 5, 8, 12, 16, 20, 25, 30, 35, 40, 45, 50):
        i = int(ms / 1000 * rate)
        da, db = 20 * np.log10(ea[i] + 1e-9), 20 * np.log10(eb[i] + 1e-9)
        diffs.append(da - db)
        print(f"  {ms:3d} ms              {da:7.1f}   {db:8.1f}   {da - db:+6.1f}")
    print(f"  worst |diff| {max(abs(d) for d in diffs):.1f} dB, mean |diff| {np.mean(np.abs(diffs)):.1f} dB")

    m = int(0.06 * rate)  # the first 60 ms hold all the energy
    # Total energy per band, not the loudest bin: a peak-only check misses broadband energy.
    ba, bb = np.fft.rfft(a[:m]), np.fft.rfft(b[:m])
    fb = np.fft.rfftfreq(m, 1 / rate)
    print("\nenergy per band (dB re total)     synth   reference    diff")
    ta, tb = (np.abs(ba) ** 2).sum(), (np.abs(bb) ** 2).sum()
    for lo, hi in ((0, 1000), (1000, 1800), (1800, 3500), (3500, 6000), (6000, 12000)):
        sel = (fb >= lo) & (fb < hi)
        ea_, eb_ = (10 * np.log10((np.abs(ba[sel]) ** 2).sum() / ta + 1e-12),
                    10 * np.log10((np.abs(bb[sel]) ** 2).sum() / tb + 1e-12))
        print(f"  {lo:5d}-{hi:<5d} Hz              {ea_:7.1f}   {eb_:8.1f}   {ea_ - eb_:+6.1f}")

    resid = rms(a - b) / rms(b)
    print(f"\nwaveform null: residual is {20 * np.log10(resid):.1f} dB re the reference "
          f"(phase-sensitive; the ear does not hear this)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("outdir", type=Path)
    ap.add_argument("--compare", type=Path, metavar="WAV", help="reference WAV at 44.1 or 48 kHz")
    args = ap.parse_args()

    args.outdir.mkdir(parents=True, exist_ok=True)
    for name, rate in RATES.items():
        path = args.outdir / f"digital-tock-{name}.wav"
        write_wav(path, synth(rate), rate)
        print(f"wrote {path}")

    if args.compare:
        ref, rate = read_wav(args.compare)
        print()
        compare(synth(rate), ref, rate)
    return 0


if __name__ == "__main__":
    sys.exit(main())
