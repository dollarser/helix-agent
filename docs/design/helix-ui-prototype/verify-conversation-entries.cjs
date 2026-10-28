/* Offline behavioral checks. This does not establish rendered layout acceptance. */
const fs = require('fs'), vm = require('vm'), assert = require('assert/strict');
const {parseHTML} = require(process.env.DOM_MODULE || 'linkedom');
const html = fs.readFileSync(__dirname + '/conversation-entries.html', 'utf8');
const {window} = parseHTML(html), {document} = window;
let focused;
window.HTMLElement.prototype.focus = function () { focused = this; };
Object.defineProperty(document, 'activeElement', {get: () => focused});
const ctx = vm.createContext({document, console});
vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], ctx);
const state = () => vm.runInContext('S', ctx);
const open = type => vm.runInContext(`open(${JSON.stringify(type)})`, ctx);
const click = action => {
  const b = document.querySelector(`[data-action="${action}"]`);
  assert.ok(b, 'Missing action: ' + action);
  b.dispatchEvent(new window.Event('click', {bubbles: true}));
};
const sheet = () => document.querySelector('.sheet');
let n = 0;
function test(name, run) { run(); n++; console.log('PASS ' + name); }
test('Add contains materials and existing capability selection, no settings', () => {
  open('add'); assert.match(sheet().textContent, /引用其他会话/);
  assert.match(sheet().textContent, /选择专家/); assert.match(sheet().textContent, /选择插件/);
  assert.doesNotMatch(sheet().textContent, /会话设置|模式命令|创建专家/);
});
test('Top and composer menus have separate responsibilities', () => {
  open('top'); assert.match(sheet().textContent, /重命名|导出会话/);
  assert.doesNotMatch(sheet().textContent, /推理强度/);
  open('run'); assert.match(sheet().textContent, /立即发送|排队/);
  assert.doesNotMatch(sheet().textContent, /推理强度|预算|上下文使用/);
  assert.doesNotMatch(sheet().textContent, /重命名|导出会话|选择专家/);
});
test('References list actual demo rows and selection attaches a chip', () => {
  open('references'); assert.equal(document.querySelectorAll('#results button').length, 3);
  click('reference:cancel'); assert.equal(state().reference.id, 'cancel');
  assert.match(document.querySelector('#chips').textContent, /取消与恢复设计/);
});
test('Reference search distinguishes no match from empty directory', () => {
  open('references'); vm.runInContext("results('NOT_PRESENT')", ctx);
  assert.match(sheet().textContent, /没有匹配/);
  state().directory = 'empty'; vm.runInContext("results('')", ctx);
  assert.match(sheet().textContent, /没有其他已有会话/);
});
test('Reference loading and failure are explicit; retry restores rows', () => {
  state().directory = 'loading'; open('references'); assert.match(sheet().textContent, /正在读取/);
  state().directory = 'error'; open('references'); assert.match(sheet().textContent, /读取失败/);
  click('retry'); assert.equal(document.querySelectorAll('#results button').length, 3);
});
test('Reference panel never includes memory configuration', () => {
  open('references'); assert.doesNotMatch(sheet().textContent, /记忆|自动写入/);
});
test('Expert selection chooses saved profile without an editor', () => {
  open('experts'); assert.equal(sheet().querySelectorAll('textarea').length, 0);
  click('expert:android'); assert.equal(state().expert.id, 'android');
});
test('Expert management returns to original selector and cancellation does not save', () => {
  open('experts'); click('manage'); click('edit:new');
  document.querySelector('#expert-name').value = 'Cancelled';
  click('open:expert-library'); assert.equal(state().experts.length, 2);
  click('return-experts'); assert.equal(state().modal, 'experts');
  assert.equal(state().expert.id, 'android');
});
test('Creating a profile does not automatically select it', () => {
  open('expert-library'); click('edit:new');
  document.querySelector('#expert-name').value = '审查员';
  document.querySelector('#expert-body').value = '检查事实。'; click('expert-save');
  assert.equal(state().experts.length, 3); assert.equal(state().expert.id, 'android');
});
test('Malformed import fails visibly without saving', () => {
  open('import-expert'); document.querySelector('#expert-json').value = '{'; click('import');
  assert.match(document.querySelector('#form-error').textContent, /JSON/);
  assert.equal(state().experts.length, 3);
});
test('Expert field limits reject invalid content without persistence', () => {
  open('expert-library'); click('edit:new');
  const before = state().experts.length;
  document.querySelector('#expert-name').value = 'x'.repeat(81);
  document.querySelector('#expert-body').value = 'valid'; click('expert-save');
  assert.equal(state().experts.length, before);
  assert.match(document.querySelector('#form-error').textContent, /80/);
  document.querySelector('#expert-name').value = 'valid';
  document.querySelector('#expert-body').value = 'x'.repeat(8193); click('expert-save');
  assert.equal(state().experts.length, before);
  open('import-expert');
});
test('Valid import adds a saved expert without selecting it', () => {
  document.querySelector('#expert-json').value = JSON.stringify({name:'导入示例', instruction:'检查来源。'});
  click('import'); assert.equal(state().experts.length, 4); assert.equal(state().expert.id, 'android');
});
test('Plugin opens one selector directly; cancellation discards candidate selection', () => {
  open('add'); click('open:plugins'); assert.equal(document.querySelectorAll('[role="dialog"]').length, 1);
  state().candidate.push('浏览器'); click('close'); assert.equal(state().plugins.length, 0);
});
test('Plugin selection is applied explicitly and pending setup remains unselected', () => {
  open('plugins'); state().candidate.push('浏览器'); click('setup');
  assert.match(document.querySelector('#plugin-note').textContent, /先登录/);
  assert.equal(state().plugins.length, 0); click('plugins-save');
  assert.equal(state().plugins.join(','), '浏览器');
});
test('Skills and materials attach without implicit execution or draft replacement', () => {
  const draft = document.querySelector('#draft').value;
  open('skills'); click('skill:资料整理'); open('knowledge'); click('material:知识库 · 权限与取消说明');
  assert.equal(state().skills[0], '资料整理'); assert.equal(document.querySelector('#draft').value, draft);
});
test('Untrusted profile content renders as text', () => {
  state().experts.push({id:'unsafe', name:'<img src=x>', body:'<script>bad()</script>'});
  open('experts'); assert.equal(sheet().querySelectorAll('img,script').length, 0);
  assert.match(sheet().textContent, /<img src=x>/);
});
test('Global memory configuration is in settings and scope is explicit', () => {
  open('settings'); assert.match(sheet().textContent, /全局记忆/);
  open('session'); assert.match(sheet().textContent, /项目记忆位于项目设置/);
});
test('Editing a selected library expert preserves the session snapshot until reselection', () => {
  open('experts'); click('expert:android');
  const original = state().expert.body;
  open('expert-library'); click('edit:android');
  document.querySelector('#expert-body').value = 'Updated library instruction'; click('expert-save');
  assert.equal(state().expert.body, original);
  open('experts');
  assert.ok(!document.querySelector('[data-action="expert:android"]').textContent.includes('✓'));
  click('expert:android');
  assert.equal(state().expert.body, 'Updated library instruction');
});
test('Switch clears selected session capabilities without sending', () => {
  open('switch'); click('switch:other');
  assert.equal(state().session, 'other'); assert.equal(state().expert, null);
  assert.equal(state().plugins.length, 0); assert.equal(state().reference, null);
});
test('Returning to original session restores its own draft and selections', () => {
  document.querySelector('#draft').value = 'Other session draft';
  open('switch'); click('switch:current');
  assert.equal(state().expert.id, 'android'); assert.equal(state().plugins.join(','), '浏览器');
  assert.equal(document.querySelector('#draft').value, '重点看看返回链和权限边界。');
  open('switch'); click('switch:other');
  assert.equal(document.querySelector('#draft').value, 'Other session draft');
  assert.equal(state().expert, null);
});
test('Dictation stays inline, cancellation preserves draft, and success remains editable', () => {
  const original = document.querySelector('#draft').value;
  open('voice'); click('open:voice-finishing'); click('open:voice-error');
  assert.match(sheet().textContent, /原草稿保留/);
  click('close'); assert.equal(document.querySelector('#draft').value, original);
  open('voice'); click('open:voice-finishing'); click('open:voice-result');
  assert.ok(document.querySelector('#draft').value.startsWith(original));
  assert.match(document.querySelector('#draft').value, /模拟转写/);
});
assert.ok(!/\b(fetch|XMLHttpRequest|WebSocket)\s*\(/.test(html));
console.log(`${n} offline interaction checks passed; browser/layout acceptance is separate.`);
