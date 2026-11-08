import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Execute the exact script injected by the Android WebView, including Kotlin's dollar escape.
const kotlin = fs.readFileSync(new URL('../../app/src/main/java/app/torrtv/web/WebSubtitleProvider.kt', import.meta.url), 'utf8');
const script = kotlin.split('val probeScript = """')[1].split('""".trimIndent()')[0].replaceAll("${'$'}", '$');

function player({tracks = [], playlist = [], playerjsSubtitle = ''} = {}) {
  const captions = [];
  const context = vm.createContext({
    document: {querySelectorAll: () => tracks},
    location: {href: 'https://player.example.com/embed/123'},
    console: {info: s => captions.push(JSON.parse(s.slice('TORRTV_SUB:'.length)))},
    MutationObserver: class { observe() {} },
    setInterval: () => {}, setTimeout: () => {},
    playerjsSubtitle,
    jwplayer: () => ({getPlaylist: () => playlist}),
  });
  context.window = context;
  vm.runInContext(script, context, {timeout: 1000});
  return {context, captions};
}

test('HTML, JW Player and PlayerJS expose only explicitly Bulgarian caption tracks', () => {
  const {captions} = player({
    tracks: [
      {src: 'https://cdn.example.com/html.vtt', srclang: 'bg', label: 'Bulgarian'},
      {src: 'https://cdn.example.com/en.vtt', srclang: 'en', label: 'Bulgarian'},
      {src: 'https://cdn.example.com/unknown.vtt', srclang: '', label: 'Subtitles'},
    ],
    playlist: [{tracks: [
      {kind: 'captions', file: 'https://cdn.example.com/jw.vtt', language: 'bul', label: 'Български'},
      {kind: 'thumbnails', file: 'https://cdn.example.com/thumbs.vtt', language: 'bg'},
    ]}],
    playerjsSubtitle: '[English]https://cdn.example.com/player-en.vtt,[Български]https://cdn.example.com/player-bg.srt',
  });
  assert.deepEqual(captions.map(c => c.url).sort(), [
    'https://cdn.example.com/html.vtt', 'https://cdn.example.com/jw.vtt', 'https://cdn.example.com/player-bg.srt',
  ]);
  assert.ok(captions.every(c => c.page === 'https://player.example.com/embed/123'));
});

test('rescanning and repeated injection do not duplicate or grow the caption list without bound', () => {
  const tracks = Array.from({length: 30}, (_, n) => ({src: `https://cdn.example.com/${n}.vtt`, srclang: 'bg'}));
  const {context, captions} = player({tracks});
  context.__torrtvScanSubs();
  vm.runInContext(script, context, {timeout: 1000});
  assert.equal(captions.length, 16);
  assert.equal(new Set(captions.map(c => c.url)).size, 16);
});
