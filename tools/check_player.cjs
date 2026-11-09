// Run: node tools/check_player.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(__dirname + '/../android/app/src/main/assets/player.html', 'utf8')
  .replace('__VIDEO__', 'gdZLi9oWNZg').replace('__ORIGIN__', 'https://com.mptaptap.playlisthub');
let options, timeout, created = 0;
const context = {
  document: {title: ''}, window: {},
  setTimeout: (callback, delay) => {assert.equal(delay, 15000); timeout = callback;},
  YT: {Player: function (_, value) {options = value; created++;}}
};
vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], vm.createContext(context));
assert.equal(created, 0);
context.window.onYouTubeIframeAPIReady();
assert.equal(created, 1);
assert.equal(options.videoId, 'gdZLi9oWNZg');
assert.equal(options.playerVars.origin, 'https://com.mptaptap.playlisthub');
assert.equal(options.playerVars.autoplay || 0, 0);
assert.notEqual(options.playerVars.controls, 0);
timeout();
assert.match(context.document.title, /연결이 지연/,'An iframe that exists but is not ready must show a delay notice');
vm.runInContext(html.match(/onerror="([^"]+)"/)[1], context); timeout();
assert.match(context.document.title, /YouTube 연결에 실패/,'A script load failure must not be replaced by a delay notice');
options.events.onError({data: 150}); timeout();
assert.match(context.document.title, /재생할 수 없어요.*150.*외부/,'A diagnosed error must not be replaced by a delay notice');
options.events.onReady(); timeout();
assert.match(context.document.title, /재생 버튼/,'A ready player must keep its user-play instruction');
options.events.onStateChange({data: 1});
assert.match(context.document.title, /재생 중/);
options.events.onError({data: 150});
assert.match(context.document.title, /재생할 수 없어요.*150.*외부/);
console.log('PASS: player readiness timeout, user-play defaults, origin, playback/error status. Live playback is checked separately.');
