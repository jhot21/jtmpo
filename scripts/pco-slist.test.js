const test = require('node:test');
const assert = require('node:assert/strict');
const { mapSong, mapPlan, buildJtmpo, songKey } = require('./pco-slist.user.js');

const song = (over = {}) => ({ title: 'Song', bpm: 100, meter: '4/4', key: null, author: null, ...over });

test('4/4 and 3/4 count the bottom-number note with one click per beat', () => {
  assert.deepEqual(mapSong(song({ bpm: 120 })).item.rhythm,
    { tempo: 120, tsNum: 4, tsDen: 4, patDen: 4, patDotted: false });
  assert.deepEqual(mapSong(song({ meter: '3/4' })).item.rhythm,
    { tempo: 100, tsNum: 3, tsDen: 4, patDen: 4, patDotted: false });
});

test('6/8 counts dotted quarters: the eighth-note BPM is divided by 3 and the click is a dotted quarter', () => {
  assert.deepEqual(mapSong(song({ bpm: 156, meter: '6/8' })).item.rhythm,
    { tempo: 52, tsNum: 6, tsDen: 8, patDen: 4, patDotted: true });
});

test('9/8 and 12/8 are compound too', () => {
  assert.deepEqual(mapSong(song({ bpm: 180, meter: '12/8' })).item.rhythm,
    { tempo: 60, tsNum: 12, tsDen: 8, patDen: 4, patDotted: true });
  assert.equal(mapSong(song({ bpm: 180, meter: '9/8' })).item.rhythm.tempo, 60);
});

test('6/4 is a simple meter with quarter clicks and no warning', () => {
  const result = mapSong(song({ bpm: 60, meter: '6/4' }));
  assert.deepEqual(result.item.rhythm, { tempo: 60, tsNum: 6, tsDen: 4, patDen: 4, patDotted: false });
  assert.deepEqual(result.warnings, []);
});

test('7/8 is simple: the eighth counts and clicks', () => {
  assert.deepEqual(mapSong(song({ bpm: 200, meter: '7/8' })).item.rhythm,
    { tempo: 200, tsNum: 7, tsDen: 8, patDen: 8, patDotted: false });
});

test('a blank meter is assumed 4/4 and flagged', () => {
  const result = mapSong(song({ meter: null }));
  assert.equal(result.item.rhythm.tsNum, 4);
  assert.match(result.warnings[0], /no meter/);
});

test('songs without a BPM or a title are skipped with a warning', () => {
  for (const bpm of [null, undefined, 0]) {
    const result = mapSong(song({ bpm }));
    assert.equal(result.item, undefined);
    assert.match(result.warnings[0], /no BPM/);
  }
  assert.equal(mapSong(song({ title: '   ' })).item, undefined);
});

test('meters jtmpo cannot represent are skipped', () => {
  for (const meter of ['4/3', '5/16x', 'free', '0/4']) {
    const result = mapSong(song({ meter }));
    assert.equal(result.item, undefined, meter);
    assert.equal(result.warnings.length, 1, meter);
  }
});

test('titles are trimmed', () => {
  assert.equal(mapSong(song({ title: 'HYMN: Example ' })).item.title, 'HYMN: Example');
});

test('the author becomes the artist, trimmed, and blank means none', () => {
  assert.equal(mapSong(song({ author: ' Example Writer ' })).item.artist, 'Example Writer');
  assert.equal(mapSong(song({ author: '  ' })).item.artist, null);
  assert.equal(mapSong(song({ author: null })).item.artist, null);
});

test("the plan's key becomes the entry's note", () => {
  assert.equal(mapSong(song({ key: 'Bb' })).item.notes, 'Key: Bb');
  assert.equal(mapSong(song({ key: null })).item.notes, null);
  assert.equal(mapSong(song({ key: '  ' })).item.notes, null);
});

test('mapPlan keeps plan order and collects warnings', () => {
  const { items, warnings } = mapPlan([
    song({ title: 'One' }), song({ title: 'Two', bpm: null }), song({ title: 'Three', meter: null }),
  ]);
  assert.deepEqual(items.map((i) => i.title), ['One', 'Three']);
  assert.equal(warnings.length, 2);
});

test('the file has the .jtmpo header, library songs and played entries', () => {
  const { items } = mapPlan([song({ title: 'One', key: 'E', author: 'A' }), song({ title: 'Two', meter: '6/8', bpm: 156 })]);
  const file = JSON.parse(buildJtmpo('Oct 11', items));
  assert.equal(file.type, 'jtmpo-setlist');
  assert.equal(file.version, 1);
  assert.equal(file.name, 'Oct 11');
  assert.deepEqual(file.songs[0], {
    id: 1, title: 'One', artist: 'A', tempo: 100, tsNum: 4, tsDen: 4, patDen: 4, patDotted: false, notes: null,
  });
  assert.deepEqual(file.entries[0], {
    songId: 1, tempo: 100, tsNum: 4, tsDen: 4, patDen: 4, patDotted: false, notes: 'Key: E',
  });
  assert.equal(file.songs[1].tempo, 52);
  assert.equal(file.entries[1].songId, 2);
});

test('the same song twice stays one library song with two entries that keep their own rhythm', () => {
  const { items } = mapPlan([
    song({ title: 'Reprise', bpm: 80, key: 'D' }), song({ title: 'Other' }), song({ title: ' reprise ', bpm: 90, key: 'E' }),
  ]);
  const file = JSON.parse(buildJtmpo('Set', items));
  assert.equal(file.songs.length, 2);
  assert.equal(file.songs[0].tempo, 80);
  assert.deepEqual(file.entries.map((e) => [e.songId, e.tempo, e.notes]), [[1, 80, 'Key: D'], [2, 100, null], [1, 90, 'Key: E']]);
});

test('the file obeys the codec rules: unique song keys, known song ids, no blank titles', () => {
  const { items } = mapPlan([
    song({ title: 'A', author: 'X' }), song({ title: 'a', author: 'Y' }), song({ title: 'A', author: 'x' }),
  ]);
  const file = JSON.parse(buildJtmpo('Set', items));
  const keys = file.songs.map((s) => songKey(s.title, s.artist));
  assert.equal(new Set(keys).size, keys.length);
  const ids = new Set(file.songs.map((s) => s.id));
  assert.equal(ids.size, file.songs.length);
  for (const e of file.entries) assert.ok(ids.has(e.songId));
  assert.ok(file.songs.every((s) => s.title.trim().length > 0));
});

test('songKey matches the app: trim, lowercase, blank artist equals none', () => {
  assert.equal(songKey(' École ', ' X '), songKey('école', 'x'));
  assert.equal(songKey('A', null), songKey('A', '  '));
  assert.notEqual(songKey('a b', null), songKey('a', 'b'));
});

test('titles with quotes and unicode survive JSON', () => {
  const { items } = mapPlan([song({ title: 'He said "Hi" & left — Ünï' })]);
  assert.equal(JSON.parse(buildJtmpo('Set', items)).songs[0].title, 'He said "Hi" & left — Ünï');
});

// scripts/fixtures/sample.jtmpo is also decoded by the app's own codec in
// app/src/test/.../share/UserscriptFixtureTest.kt, so a format change fails on both sides.
const SAMPLE_PLAN = [
  { title: 'Example Hymn ', bpm: 90, meter: '3/4', key: 'D', author: 'A. Writer' },
  { title: 'Example Waltz', bpm: 156, meter: '6/8', key: 'G', author: 'B. Writer and C. Writer' },
  { title: 'Example Slow', bpm: 60, meter: '6/4', key: null, author: null },
  { title: 'Example Hymn', bpm: 84, meter: '3/4', key: 'E', author: 'A. Writer' },
  { title: 'No Meter Song', bpm: 100, meter: null, key: 'Ab', author: null },
];

test('the output matches the shared fixture the app also decodes', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const { items } = mapPlan(SAMPLE_PLAN);
  const actual = buildJtmpo('Sample Set', items) + '\n';
  const fixture = path.join(__dirname, 'fixtures', 'sample.jtmpo');
  if (process.env.UPDATE_FIXTURE) fs.writeFileSync(fixture, actual);
  assert.equal(fs.readFileSync(fixture, 'utf8'), actual);
});
