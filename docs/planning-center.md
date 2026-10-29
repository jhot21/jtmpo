# Exporting a Planning Center plan

`scripts/pco-slist.user.js` is a userscript that adds a "Download .slist" button to Planning Center
Services plan pages. The file it downloads imports through Settings -> Import
(see `import-format.md`).

## Install

1. Install a userscript manager such as Violentmonkey or Tampermonkey.
2. Create a new script and paste in `scripts/pco-slist.user.js`.
3. Open a plan at `services.planningcenteronline.com/plans/<id>` while logged in and click the button.

The script reads the plan with your logged-in browser session, so there is no token to create or
store. If Planning Center refuses the request (401/403), the button reports it.

## Mapping

Each song item in the plan uses its arrangement's BPM and meter.

| Planning Center | Result |
|---|---|
| `n/4` | `n` beats, quarter-note clicks, BPM unchanged |
| `6/8` | BPM divided by 3 (Planning Center counts eighths, jtmpo's 6/8 counts dotted quarters) |
| `6/4` | eighth-note clicks, because the importer reads 6 beats without a subdivision as 6/8 |
| blank meter | assumed 4/4 |
| no BPM, or a meter such as `12/8` | song skipped |

Assumed, subdivided and skipped songs are listed after the download so you can check them.

## Tests

```bash
mise exec -- task test:scripts
```
