// ==UserScript==
// @name         Planning Center plan to jtmpo .slist
// @namespace    https://github.com/jhot21/jtmpo
// @version      1.0.0
// @description  Adds a button to Planning Center Services plan pages that downloads the plan's songs as a Tempo .slist for jtmpo.
// @match        https://services.planningcenteronline.com/plans/*
// @grant        none
// ==/UserScript==

// Reads the plan with the logged-in browser session, so no token is stored. See docs/planning-center.md.
// The mapping functions are pure and exported for scripts/pco-slist.test.js.

const API = 'https://api.planningcenteronline.com/services/v2';

// A blank meter is common in Planning Center, so it is assumed rather than dropped.
const DEFAULT_METER = '4/4';

function mapSong(title, bpm, meter) {
  const name = title.trim();
  if (!bpm) return { warning: `${name}: no BPM, skipped` };

  const warnings = [];
  let m = meter;
  if (!m) {
    m = DEFAULT_METER;
    warnings.push(`${name}: no meter, assumed ${DEFAULT_METER}`);
  }
  const match = /^(\d+)\/(\d+)$/.exec(m.trim());
  if (!match) return { warning: `${name}: unreadable meter "${meter}", skipped` };
  const beats = Number(match[1]);
  const unit = Number(match[2]);

  let song;
  if (beats === 6 && unit === 8) {
    // jtmpo's 6/8 counts dotted quarters; Planning Center counts eighths.
    song = { title: name, tempo: Math.round(bpm / 3), numBeats: 6, numSubBeats: 1, beatCode: 0 };
  } else if (beats === 6 && unit === 4) {
    // The importer reads 6 beats with no subdivision as 6/8, so 6/4 needs eighth-note clicks.
    song = { title: name, tempo: bpm, numBeats: 6, numSubBeats: 2, beatCode: 1 };
    warnings.push(`${name}: 6/4 imports with eighth-note clicks`);
  } else if (unit === 4 && beats >= 1) {
    song = { title: name, tempo: bpm, numBeats: beats, numSubBeats: 1, beatCode: 1 };
  } else {
    return { warning: `${name}: meter ${m} is not supported, skipped` };
  }
  return { song, warnings };
}

function mapPlan(entries) {
  const songs = [];
  const warnings = [];
  for (const { title, bpm, meter } of entries) {
    const result = mapSong(title, bpm, meter);
    if (result.song) songs.push(result.song);
    warnings.push(...(result.warnings ?? []), ...(result.warning ? [result.warning] : []));
  }
  return { songs, warnings };
}

// Control characters other than tab and newlines are not valid in XML 1.0.
const xmlEscape = (s) =>
  s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function buildSlist(title, songs) {
  const int = (k, v) => `<key>${k}</key><integer>${v}</integer>`;
  const dicts = songs.map((s) =>
    `<dict><key>title</key><string>${xmlEscape(s.title)}</string>` +
    int('tempo', s.tempo) + int('beatCode', s.beatCode) +
    int('numBeats', s.numBeats) + int('numSubBeats', s.numSubBeats) + '</dict>');
  return `<?xml version='1.0' encoding='UTF-8' ?><plist version="1.0"><dict>` +
    `<key>title</key><string>${xmlEscape(title)}</string><key>songs</key><array>${dicts.join('')}</array></dict></plist>`;
}

if (typeof module !== 'undefined') module.exports = { mapSong, mapPlan, buildSlist };

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
  let url = `${API}/plans/${planId}/items?include=arrangement&per_page=100`;
  while (url) {
    const page = await getJson(url);
    const arrangements = new Map(
      (page.included ?? []).filter((i) => i.type === 'Arrangement').map((i) => [i.id, i.attributes]));
    for (const item of page.data) {
      if (item.attributes.item_type !== 'song') continue;
      const arrangement = arrangements.get(item.relationships.arrangement?.data?.id);
      entries.push({ title: item.attributes.title, bpm: arrangement?.bpm, meter: arrangement?.meter });
    }
    url = page.links?.next ?? null;
  }
  const plan = await getJson(`/plans/${planId}.json`);
  return { title: plan.short_dates || `Plan ${planId}`, entries };
}

function download(filename, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/xml' }));
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
    const { songs, warnings } = mapPlan(entries);
    if (!songs.length) throw new Error('No songs with a BPM were found in this plan.');
    download(`${title.replace(/[^\w.-]+/g, '-')}.slist`, buildSlist(title, songs));
    alert(`Downloaded ${songs.length} song${songs.length === 1 ? '' : 's'}.` +
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
  const button = Object.assign(document.createElement('button'), { id, textContent: 'Download .slist' });
  button.style.cssText = 'position:fixed;bottom:16px;right:16px;z-index:9999;padding:8px 14px;' +
    'border:0;border-radius:6px;background:#2d6cdf;color:#fff;font:600 14px sans-serif;cursor:pointer';
  button.addEventListener('click', () => onClick(button));
  document.body.append(button);
}

if (typeof document !== 'undefined') setInterval(syncButton, 1000);
