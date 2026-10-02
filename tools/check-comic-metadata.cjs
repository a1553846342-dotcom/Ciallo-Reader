// No Gradle/build: exercise the actual constructors, metadata shim, and bridge normalizer.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const runtime = read('app/src/main/assets/venera/_venera_.js');
const engine = read('app/src/main/java/com/example/source/js/JsSourceEngine.kt');
const context = vm.createContext({});
for (const name of ['Comic', 'ComicDetails']) {
    const start = runtime.indexOf(`function ${name}(`);
    const end = runtime.indexOf('\n/**', start);
    assert(start >= 0 && end > start, `Missing ${name} constructor`);
    vm.runInContext(runtime.slice(start, end), context);
}
vm.runInContext(read('app/src/main/assets/venera/comic_metadata.js'), context);
const start = engine.indexOf('const __flattenChapters =');
const end = engine.indexOf('                try {', start);
assert(start >= 0 && end > start, 'Missing bridge normalizer');
vm.runInContext(engine.slice(start, end) + '\nglobalThis.normalize = __normalize;', context);
const evaluate = expression => JSON.parse(vm.runInContext(`JSON.stringify(${expression})`, context));
const metadata = evaluate(`normalize(new ComicDetails({
    title: '测试漫画', author: ['作者 A', '作者 B'], artist: '画师 C', status: 'completed',
    language: 'zh', alternateTitles: ['Another Title'], updateTime: '2026-10-01',
    tags: new Map([['作者', ['作者 A']], ['类型', ['冒险', '校园']]]),
    chapters: new Map([['第一卷', new Map([['ep1', '第1话'], ['ep2', '第2话']])]])
}))`);
assert.deepEqual(metadata.author, ['作者 A', '作者 B']);
assert.equal(metadata.artist, '画师 C');
assert.equal(metadata.status, 'completed');
assert.equal(metadata.language, 'zh');
assert.deepEqual(metadata.alternateTitles, ['Another Title']);
assert.deepEqual(metadata.tags, { 作者: ['作者 A'], 类型: ['冒险', '校园'] });
assert.deepEqual(metadata.chapters, [
    { id: 'ep1', title: '第1话', group: '第一卷' },
    { id: 'ep2', title: '第2话', group: '第一卷' },
]);
const search = evaluate(`normalize(new Comic({id: 'id', title: '标题', subtitle: '最近更新 12 话', author: '真实作者', aliases: ['别名']}))`);
assert.equal(search.author, '真实作者');
assert.equal(search.subtitle, '最近更新 12 话');
assert.deepEqual(search.aliases, ['别名']);
const plain = evaluate(`normalize(new ComicDetails({title:'普通作品', tags:{作者:['甲']}, chapters:{ep:'第一话'}}))`);
assert.deepEqual(plain.tags, { 作者: ['甲'] });
assert.deepEqual(plain.chapters, [{ id: 'ep', title: '第一话' }]);
assert.equal(Object.hasOwn(plain, 'author'), false);
assert.equal(vm.runInContext('new ComicDetails({title:"x"}) instanceof ComicDetails', context), true);
console.log('PASS: constructor metadata, Map/object tags, nested/flat chapters, missing author, constructor compatibility. No build invoked.');
