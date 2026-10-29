const test = require('node:test');
const assert = require('node:assert/strict');
const { mapSong, mapPlan, buildSlist } = require('./pco-slist.user.js');

test('4/4 and 3/4 map to quarter-note clicks at the given tempo', () => {
  assert.deepEqual(mapSong('A', 120, '4/4').song,
    { title: 'A', tempo: 120, numBeats: 4, numSubBeats: 1, beatCode: 1 });
  assert.equal(mapSong('B', 90, '3/4').song.numBeats, 3);
});

test('6/8 counts dotted quarters, so the eighth-note BPM is divided by 3', () => {
  const { song } = mapSong('A', 156, '6/8');
  assert.deepEqual(song, { title: 'A', tempo: 52, numBeats: 6, numSubBeats: 1, beatCode: 0 });
});

test('6/4 is subdivided so the importer does not read it as 6/8, and is flagged', () => {
  const { song, warnings } = mapSong('A', 60, '6/4');
  assert.equal(song.numBeats, 6);
  assert.equal(song.numSubBeats, 2);
  assert.equal(song.tempo, 60);
  assert.equal(warnings.length, 1);
});

test('a blank meter is assumed 4/4 and flagged', () => {
  const { song, warnings } = mapSong('A', 68, null);
  assert.equal(song.numBeats, 4);
  assert.match(warnings[0], /no meter/);
});

test('songs without a BPM are skipped with a warning', () => {
  for (const bpm of [null, undefined, 0]) {
    const result = mapSong('A', bpm, '4/4');
    assert.equal(result.song, undefined);
    assert.match(result.warning, /no BPM/);
  }
});

test('meters the importer cannot represent are skipped', () => {
  for (const meter of ['12/8', '9/8', '5/16', 'free']) {
    const result = mapSong('A', 100, meter);
    assert.equal(result.song, undefined, meter);
    assert.ok(result.warning, meter);
  }
});

test('titles are trimmed', () => {
  assert.equal(mapSong('HYMN: Example ', 80, '4/4').song.title, 'HYMN: Example');
});

test('mapPlan keeps plan order and collects warnings', () => {
  const { songs, warnings } = mapPlan([
    { title: 'One', bpm: 100, meter: '4/4' },
    { title: 'Two', bpm: null, meter: '4/4' },
    { title: 'Three', bpm: 70, meter: null },
  ]);
  assert.deepEqual(songs.map((s) => s.title), ['One', 'Three']);
  assert.equal(warnings.length, 2);
});

test('the slist has the five fields the importer requires for every song', () => {
  const xml = buildSlist('Set', [mapSong('A', 100, '4/4').song, mapSong('B', 150, '6/8').song]);
  const dicts = xml.match(/<dict><key>title<\/key><string>[^<]*<\/string>.*?<\/dict>/g);
  assert.equal(dicts.length, 2);
  for (const d of dicts) {
    for (const key of ['title', 'tempo', 'numBeats', 'numSubBeats', 'beatCode']) {
      assert.ok(d.includes(`<key>${key}</key>`), key);
    }
  }
  assert.ok(!/DOCTYPE|ENTITY/.test(xml));
});

test('titles are XML-escaped and invalid control characters dropped', () => {
  const xml = buildSlist('A & B', [{ title: 'Tom & <Jerry>\u0001', tempo: 100, numBeats: 4, numSubBeats: 1, beatCode: 1 }]);
  assert.ok(xml.includes('<string>A &amp; B</string>'));
  assert.ok(xml.includes('Tom &amp; &lt;Jerry&gt;</string>'));
});
