// Run: node experiments/youtube-player/check.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(__dirname + '/index.html', 'utf8');
const elements = Object.fromEntries(['form','video','status','load','original'].map(id => [id, {}]));
let submit, events, created = 0, cued;
elements.form.addEventListener = (_, handler) => { submit = handler; };
const context = {
  document: {getElementById: id => elements[id]}, window: {}, location: {origin:'http://localhost:8765'},
  YT: {Player: function (_, options) {
    created++; events = options.events;
    this.cueVideoById = id => { cued = id; };
    assert.equal(options.playerVars.origin, context.location.origin);
  }}
};
vm.runInNewContext(html.match(/<script id="experiment">([\s\S]*?)<\/script>/)[1], context);
context.window.onYouTubeIframeAPIReady();
assert.equal(elements.load.disabled, false);
elements.video.value = 'https://evil.example';
submit({preventDefault(){}});
assert.equal(created, 0, 'Invalid input must not create a player');
elements.video.value = 'gdZLi9oWNZg';
submit({preventDefault(){}});
assert.equal(created, 1);
events.onStateChange({data:1});
assert.match(elements.status.textContent, /재생 중/);
events.onError({data:153});
assert.match(elements.status.textContent, /출처 식별 실패/);
elements.video.value = 'BflFNMl_UWY';
submit({preventDefault(){}});
assert.equal(created, 1, 'Reuse the existing player');
assert.equal(cued, elements.video.value);
assert.ok(elements.original.href.endsWith(cued));
console.log('PASS: input validation, player reuse, playback/error status, original link. Live playback requires browser testing.');
