// ==UserScript==
// @name         Planning Center plan to jtmpo setlist
// @namespace    https://github.com/jhot21/jtmpo
// @version      2.0.0
// @description  Adds a button to Planning Center Services plan pages that downloads the plan's songs as a jtmpo setlist (.jtmpo) you can open in jtmpo.
// @match        https://services.planningcenteronline.com/plans/*
// @grant        none
// ==/UserScript==

// Reads the plan with the logged-in browser session, so no token is stored. See docs/planning-center.md.
// The mapping functions are pure and exported for scripts/pco-slist.test.js. The output format is
// described in docs/import-format.md ("The .jtmpo format").

const PCO_API = 'https://api.planningcenteronline.com/services/v2';

// Planning Center's song author (the songwriter) becomes the song's artist. Set to false to leave
// artists blank, which matches songs previously imported from Tempo.
const USE_AUTHOR_AS_ARTIST = true;

// A blank meter is common in Planning Center, so it is assumed rather than dropped.
const DEFAULT_METER = '4/4';

const SUPPORTED_DENOMINATORS = [2, 4, 8, 16];
const COMPOUND_NUMERATORS = [6, 9, 12];

// jtmpo's rhythm, as stored in the file. Tempo counts the beat note: the dotted beat in compound
// meters (6, 9 or 12 over 8 or 16), otherwise the bottom-number note. The click pattern defaults to
// one click per beat.
function rhythmFor(bpm, beats, unit) {
  const compound = [8, 16].includes(unit) && COMPOUND_NUMERATORS.includes(beats);
  return compound
    // Planning Center counts the eighths in 6/8; jtmpo counts the dotted quarters.
    ? { tempo: Math.round(bpm / 3), tsNum: beats, tsDen: unit, patDen: unit / 2, patDotted: true }
    : { tempo: bpm, tsNum: beats, tsDen: unit, patDen: unit, patDotted: false };
}

function mapSong({ title, bpm, meter, key, author }) {
  const name = (title ?? '').trim();
  if (!name) return { warnings: ['A song with no title was skipped'] };
  if (!bpm) return { warnings: [`${name}: no BPM, skipped`] };

  const warnings = [];
  let m = meter;
  if (!m) {
    m = DEFAULT_METER;
    warnings.push(`${name}: no meter, assumed ${DEFAULT_METER}`);
  }
  const match = /^(\d+)\/(\d+)$/.exec(m.trim());
  if (!match) return { warnings: [`${name}: unreadable meter "${meter}", skipped`] };
  const beats = Number(match[1]);
  const unit = Number(match[2]);
  if (beats < 1 || !SUPPORTED_DENOMINATORS.includes(unit)) {
    return { warnings: [`${name}: meter ${m} is not supported, skipped`] };
  }

  const artist = USE_AUTHOR_AS_ARTIST ? (author ?? '').trim() || null : null;
  const playedKey = (key ?? '').trim();
  return {
    item: {
      title: name,
      artist,
      rhythm: rhythmFor(bpm, beats, unit),
      // The key is chosen per service, so it is the entry's note, not the library song's.
      notes: playedKey ? `Key: ${playedKey}` : null,
    },
    warnings,
  };
}

function mapPlan(entries) {
  const items = [];
  const warnings = [];
  for (const entry of entries) {
    const result = mapSong(entry);
    if (result.item) items.push(result.item);
    warnings.push(...result.warnings);
  }
  return { items, warnings };
}

// Must agree with songKey in the app: trimmed, lowercased title and artist, joined by a NUL.
const songKey = (title, artist) => `${title.trim().toLowerCase()}\u0000${(artist ?? '').trim().toLowerCase()}`;

// One song per distinct title and artist, taking the first appearance's rhythm as the library value.
// Every plan entry keeps the rhythm and notes it plays with, so a song used twice with different
// arrangements stays one song with two entries.
function buildJtmpo(name, items) {
  const songs = new Map();
  const entries = items.map((item) => {
    const key = songKey(item.title, item.artist);
    if (!songs.has(key)) {
      songs.set(key, { id: songs.size + 1, title: item.title, artist: item.artist, ...item.rhythm, notes: null });
    }
    return { songId: songs.get(key).id, ...item.rhythm, notes: item.notes };
  });
  return JSON.stringify({
    type: 'jtmpo-setlist',
    version: 1,
    name,
    songs: [...songs.values()],
    entries,
  }, null, 2);
}

if (typeof module !== 'undefined') module.exports = { mapSong, mapPlan, buildJtmpo, songKey };

async function getJson(url) {
  const res = await fetch(url, { credentials: 'include', headers: { Accept: 'application/json' } });
  if (res.status === 401 || res.status === 403) {
    throw new Error(`Planning Center refused the request (${res.status}). Log in and check you can view this plan.`);
  }
  if (!res.ok) throw new Error(`Planning Center returned ${res.status}`);
  return res.json();
}

async function fetchPlan(planId) {
  const entries = [];
  let url = `${PCO_API}/plans/${planId}/items?include=song,arrangement&per_page=100`;
  while (url) {
    const page = await getJson(url);
    const included = new Map((page.included ?? []).map((i) => [`${i.type}:${i.id}`, i.attributes]));
    for (const item of page.data) {
      if (item.attributes.item_type !== 'song') continue;
      const arrangement = included.get(`Arrangement:${item.relationships.arrangement?.data?.id}`);
      const song = included.get(`Song:${item.relationships.song?.data?.id}`);
      entries.push({
        title: item.attributes.title,
        key: item.attributes.key_name,
        bpm: arrangement?.bpm,
        meter: arrangement?.meter,
        author: song?.author,
      });
    }
    url = page.links?.next ?? null;
  }
  const plan = await getJson(`/plans/${planId}.json`);
  return { title: plan.short_dates || `Plan ${planId}`, entries };
}

function download(filename, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/vnd.jtmpo.setlist+json' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: filename });
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

async function onClick(button) {
  const planId = /\/plans\/(\d+)/.exec(location.pathname)?.[1];
  if (!planId) return;
  button.disabled = true;
  try {
    const { title, entries } = await fetchPlan(planId);
    const { items, warnings } = mapPlan(entries);
    if (!items.length) throw new Error('No songs with a BPM were found in this plan.');
    download(`${title.replace(/[^\w.-]+/g, '-')}.jtmpo`, buildJtmpo(title, items));
    alert(`Downloaded ${items.length} song${items.length === 1 ? '' : 's'}.` +
      (warnings.length ? `\n\nCheck:\n- ${warnings.join('\n- ')}` : ''));
  } catch (e) {
    alert(`jtmpo export failed: ${e.message}`);
  } finally {
    button.disabled = false;
  }
}

// Planning Center is a single-page app, so the button follows the URL instead of page loads.
function syncButton() {
  const id = 'jtmpo-slist-button';
  const onPlan = /^\/plans\/\d+/.test(location.pathname);
  const existing = document.getElementById(id);
  if (!onPlan) return existing?.remove();
  if (existing) return;
  const button = Object.assign(document.createElement('button'), { id, textContent: 'Download .jtmpo' });
  button.style.cssText = 'position:fixed;bottom:16px;right:16px;z-index:9999;padding:8px 14px;' +
    'border:0;border-radius:6px;background:#2d6cdf;color:#fff;font:600 14px sans-serif;cursor:pointer';
  button.addEventListener('click', () => onClick(button));
  document.body.append(button);
}

if (typeof document !== 'undefined') setInterval(syncButton, 1000);
