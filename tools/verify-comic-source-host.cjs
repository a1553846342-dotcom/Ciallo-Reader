// Lightweight checks only: no Gradle, Android runtime, emulator, or live account.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8').replace(/\r\n/g, '\n');
const acorn = require(path.join(root, 'app/src/main/assets/js_safety/acorn.js'));
const results = [];
async function check(name, run) {
  await run();
  results.push({name, status: 'passed'});
  console.log('PASS ' + name);
}
async function main() {
  await check('bundled scripts and JS bridge syntax', () => {
    for (const dir of ['app/src/main/assets/js_extra', 'app/src/main/assets/js_safety']) {
      for (const name of fs.readdirSync(path.join(root, dir)).filter(n => n.endsWith('.js'))) {
        acorn.parse(read(dir + '/' + name), {ecmaVersion: 'latest'});
      }
    }
    const engine = read('app/src/main/java/com/example/source/js/JsSourceEngine.kt');
    const override = engine.split('private val SYNC_OVERRIDE = """')[1].split('""".trimIndent()')[0];
    acorn.parse(override, {ecmaVersion: 'latest'});
  });
  // Extract the production replacement from Kotlin. Execute only this owned snippet;
  // downloaded source scripts are neither evaluated nor given host capabilities.
  const repo = read('app/src/main/java/com/example/source/js/JsSourceRepo.kt');
  await check('NHentai optional API key also reaches public API headers', () => {
    const prefix = repo.split('"nhentai" -> script.replace(')[1].split('"""')[1];
    const helper = vm.runInNewContext('({' + prefix + '\n"User-Agent":"fixture",Accept:"application/json"};}})');
    helper.getApiKey = () => '';
    assert.equal(helper.getApiBaseHeaders().Authorization, undefined);
    helper.getApiKey = () => 'fixture-key';
    assert.equal(helper.getApiBaseHeaders().Authorization, 'Key fixture-key');
    assert.equal(helper.getApiBaseHeaders().Accept, 'application/json');
  });
  const batch = repo.split('patched.replaceRange(catalogueStart, catalogueEnd, """')[1]
    .split('""".trimIndent()')[0].replaceAll("${'$'}", '$');
  acorn.parse('async function audit(){\n' + batch + '\n}', {ecmaVersion: 'latest'});
  const fetchCatalogue = new (Object.getPrototypeOf(async function () {}).constructor)(
    'Network', 'Comick', 'collectChapters', 'slug',
    'let latestTimestamp = null; let lastPage = 1;\n' + batch + '\nreturn latestTimestamp;');
  await check('72 catalogue pages preserve all 4303 entries with at most 4 requests in flight', async () => {
    let active = 0, peak = 0, calls = 0;
    const entries = [];
    const timestamp = await fetchCatalogue({get: async url => {
      calls++; active++; peak = Math.max(peak, active);
      const page = Number(new URL(url).searchParams.get('page'));
      await new Promise(resolve => setTimeout(resolve, (page % 3) + 1));
      active--;
      const count = page === 72 ? 43 : 60;
      return {status: 200, body: JSON.stringify({pagination: {last_page: 72}, data:
        Array.from({length: count}, (_, index) => ({id: (page - 1) * 60 + index, updated_at: '2026-09-30'}))})};
    }}, {getRandomHeaders: () => ({})}, items => entries.push(...items), 'fixture');
    assert.equal(calls, 72);
    assert.equal(peak, 4);
    assert.equal(timestamp, '2026-09-30');
    assert.deepEqual(entries.map(item => item.id), Array.from({length: 4303}, (_, i) => i));
  });
  await check('catalogue failures and unreasonable pagination are reported', async () => {
    const headers = {getRandomHeaders: () => ({})};
    await assert.rejects(fetchCatalogue({get: async url => {
      const page = Number(new URL(url).searchParams.get('page'));
      return page === 3 ? {status: 503} : {status: 200, body: JSON.stringify({data: [], pagination: {last_page: 4}})};
    }}, headers, () => {}, 'fixture'), error => String(error).includes('503'));
    await assert.rejects(fetchCatalogue({get: async () => ({status: 200,
      body: JSON.stringify({data: [], pagination: {last_page: 513}})})}, headers, () => {}, 'fixture'),
      error => String(error).includes('page count'));
    assert.ok(repo.includes('throw error;\n            }"""'));
  });
  await check('one-use search tickets renew for each search while fresh guards are reused', async () => {
    const jar = new Map();
    let calls = 0, active = 0, peak = 0;
    const source = vm.runInNewContext(read('app/src/main/assets/js_extra/bilimanga.js') + '\nnew BiliManga()', {
      ComicSource: class {}, Network: {
        getCookies: async () => Array.from(jar, ([name, value]) => ({name, value})),
        setCookies: (_, cookies) => cookies.forEach(c => jar.set(c.name, c.value)),
        get: async url => {
          calls++; active++; peak = Math.max(peak, active);
          await new Promise(resolve => setTimeout(resolve, 1)); active--;
          if (url.includes('guard=css')) jar.set('jieqiSearchCss', 'fixture');
          if (url.includes('redeem')) jar.set('jieqiSearchTicket', 'fixture');
          return {status: 200, body: url.includes('guard=js') ? 'document.cookie="jieqiSearchJs=fixture; path=/";' : ''};
        }
      }
    });
    await source.ensureSearchTicket();
    assert.equal(calls, 3);
    assert.equal(peak, 2);
    assert.equal(jar.get('jieqiSearchJs'), 'fixture');
    await source.ensureSearchTicket();
    assert.equal(calls, 4);
    source.searchGuardsReadyAt -= 11 * 60 * 1000;
    await source.ensureSearchTicket();
    assert.equal(calls, 7);
    assert.ok(source.readerHeaders()['User-Agent'].includes('Mobile'));
  });
  await check('cookie bridge JSON keeps names, values containing equals, and deterministic writes', async () => {
    const engine = read('app/src/main/java/com/example/source/js/JsSourceEngine.kt');
    const override = engine.split('private val SYNC_OVERRIDE = """')[1].split('""".trimIndent()')[0];
    const start = override.indexOf('Network.getCookies =');
    const end = override.indexOf('// ---- 工具函数', start);
    let writes = 0;
    const context = {Network: {}, sendMessage: async () => '[{"name":"ticket","value":"a=b"}]',
      sendMessageSync: () => { writes++; return null; }};
    vm.runInNewContext(override.slice(start, end), context);
    const cookies = await context.Network.getCookies('https://fixture.invalid/');
    assert.equal(cookies[0].name, 'ticket'); assert.equal(cookies[0].value, 'a=b');
    context.Network.setCookies('https://fixture.invalid/', cookies);
    assert.equal(writes, 1);
  });
  await check('480 image batches preserve order, cap concurrency and reject partial chapters', async () => {
    const prefix = repo.split('patched = patched.replace("    loadEp: async (comicId, epId) => {", """')[1]
      .split('""".trimIndent())')[0];
    const code = '({' + prefix + '\nthrow new Error("Unexpected old-domain fallback");\n},})';
    acorn.parse(code, {ecmaVersion: 'latest'});
    async function run(partial) {
      let active = 0, peak = 0, calls = 0;
      const source = vm.runInNewContext(code, {
        Ikm: {baseUrl: 'https://ymcdnyfqdapp.ikmmh.com', webHeaders: {}, jsonHead: {}},
        setTimeout: callback => callback(),
        validatorGet: async () => ({status: 200}),
        validatorPost: async (_url, _headers, body) => {
          const offset = Number(new URLSearchParams(body).get('offset'));
          calls++; active++; peak = Math.max(peak, active);
          await new Promise(resolve => setTimeout(resolve, (offset % 3) + 1));
          active--;
          const length = partial && offset === 30 ? 9 : 10;
          return {status: 200, batch: {ok: true, total: 480,
            images: Array.from({length}, (_, i) => 'page-' + (offset + i))}};
        },
        parseReadPicsImages: response => response.batch,
      });
      const result = await source.loadEp('fixture', '/chapter/24/100.html');
      assert.equal(calls, 48); assert.equal(peak, 2);
      assert.deepEqual(Array.from(result.images), Array.from({length: 480}, (_, i) => 'page-' + i));
    }
    await run(false);
    await assert.rejects(run(true), error => String(error).includes('不完整'));
  });
  fs.mkdirSync(path.join(root, '.source-verification'), {recursive: true});
  fs.writeFileSync(path.join(root, '.source-verification/host-static-regression.json'), JSON.stringify({
    environment: 'Node host, synthetic fixtures; not Android or live site verification', results
  }, null, 2));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
