const assert = require('node:assert/strict');
const path = require('node:path');
const { createRequire } = require('node:module');
const { pathToFileURL } = require('node:url');
const root = path.resolve(__dirname, '../frontend');
const localRequire = createRequire(path.join(root, 'package.json'));
const { chromium } = require(process.argv[2] || 'playwright');

async function main() {
  const { preview } = await import(pathToFileURL(localRequire.resolve('vite')).href);
  const server = await preview({ root, configFile: false, preview: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
  let browser;
  try {
    browser = await chromium.launch({ channel: 'msedge', headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    const company = { id: 1, type: 'COMPANY', name: '公司' }, location = { id: 2, type: 'LOCATION', name: '仓库' };
    const model = { id: 3, type: 'MODEL', name: 'ThinkPad L13' }, category = { id: 4, type: 'CATEGORY', name: '笔记本电脑', assetProfile: 'COMPUTER' };
    const status = { id: 5, type: 'STATUS', name: '当前可用' };
    const assets = [10, 11, 12].map(id => ({ id, version: 0, assetTag: `20261125${id}`, name: `电脑${id}`, company, location, model, category, status,
      checkedOut: false, assignedTo: null, relatedDevices: [], accessories: [], imageUrls: [], customParameters: [], boundDisplays: [],
      domainJoinStatus: 'JOINED', knownDefects: [], createdAt: '2026-11-25T09:00:00', updatedAt: '2026-11-25T09:00:00' }));
    let records = assets.map(a => ({ assetId: a.id, version: a.id === 10 ? -1 : 0, assetVersion: 0, state: a.id === 10 ? 'UNVERIFIED' : a.id === 11 ? 'PENDING' : 'READY', reason: a.id === 12 ? '检查完成，可配发' : '尚未检查', previousHolder: '原员工', updatedAt: null }));
    let checks = Object.fromEntries(['DATA', 'SYSTEM', 'DOMAIN', 'HARDWARE'].map(key => [key, { result: 'UNCHECKED', note: '' }]));
    let events = [], posts = 0, rejectNext = false, readOnly = false;
    await page.route('**/api/**', async route => {
      const request = route.request(), pathname = new URL(request.url()).pathname;
      let data = [];
      if (pathname === '/api/auth/me') data = { id: 1, username: 'admin', displayName: 'admin', role: 'SUPER_ADMIN', permissions: readOnly ? ['ASSET_VIEW'] : ['ASSET_VIEW', 'ASSET_EDIT'], canManageUsers: true, dataScope: 'ALL', departments: [], hasAvatar: false };
      else if (pathname === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' };
      else if (pathname === '/api/dashboard') data = null;
      else if (pathname === '/api/assets/summary') data = { total: 3, available: 3, checkedOut: 0, maintenance: 0, scrapped: 0 };
      else if (pathname === '/api/assets') data = assets;
      else if (pathname === '/api/lookups') data = [company, location, model, category, status];
      else if (pathname === '/api/asset-preparations') data = records;
      else if (/^\/api\/asset-preparations\/\d+$/.test(pathname)) {
        const id = Number(pathname.split('/').pop()); let record = records.find(r => r.assetId === id);
        if (request.method() === 'POST') {
          posts++; const body = request.postDataJSON(); assert.equal(request.headers()['x-csrf-token'], 'test');
          if (rejectNext) { rejectNext = false; await route.fulfill({ status: 409, json: { message: '资产已变化，请刷新后核对' } }); return; }
          checks = body.checks; record = { ...record, version: record.version + 1, state: body.action === 'READY' ? 'READY' : 'IN_PROGRESS', reason: body.note || '检查完成，可配发' };
          records = records.map(r => r.assetId === id ? record : r); events.push({ cycle: 1, actor: 'admin', action: body.action, description: '确认整备检查', occurredAt: '2026-11-25T09:00:00' });
        }
        data = { preparation: record, note: '', checks, events };
      } else if (/^\/api\/assets\/\d+$/.test(pathname)) data = assets.find(a => a.id === Number(pathname.split('/').pop()));
      await route.fulfill({ json: data });
    });
    await page.goto(server.resolvedUrls.local[0]);
    await page.getByRole('button', { name: '资产', exact: true }).click();
    await page.getByRole('button', { name: '全部资产', exact: true }).click();
    await page.locator('.asset-name').first().waitFor();
    await page.getByLabel('筛选配发状态').selectOption('READY'); assert.equal(await page.locator('.asset-name').count(), 1);
    assert.equal(await page.locator('.asset-name').innerText(), '电脑12');
    await page.getByRole('button', { name: '整备清单', exact: true }).click();
    const card = page.locator('.preparation-cards article').filter({ hasText: '电脑11' });
    await card.getByRole('button', { name: '查看／处理' }).click();
    for (const label of ['资料与账号', '系统与软件', '入域与网络', '硬件与配件']) await page.getByLabel(label, { exact: true }).selectOption('PASS');
    await page.getByRole('button', { name: '确认可配发', exact: true }).click(); assert.equal(posts, 0);
    rejectNext = true; await page.getByRole('alertdialog').getByRole('button', { name: '确认保存', exact: true }).click();
    await page.getByRole('alert').getByText('资产已变化，请刷新后核对').waitFor();
    assert.equal(await page.getByLabel('硬件与配件', { exact: true }).inputValue(), 'PASS');
    await page.getByRole('alertdialog').getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('button', { name: '保存检查', exact: true }).click(); await page.locator('.preparation-events li').first().waitFor();
    assert.equal(posts, 2);
    for (const theme of ['light', 'dark']) for (const width of [1440, 390, 320]) {
      await page.evaluate(value => document.documentElement.dataset.theme = value, theme); await page.setViewportSize({ width, height: 900 });
      assert.equal(await page.locator('.preparation-page').evaluate(e => e.scrollWidth <= e.clientWidth + 1), true, `preparation fits ${theme}/${width}`);
    }
    readOnly = true; await page.setViewportSize({ width: 1440, height: 900 }); await page.reload();
    await page.getByRole('heading', { name: '整备清单', exact: true }).waitFor();
    if (!await page.getByRole('button', { name: '整备清单', exact: true }).isVisible()) await page.getByRole('button', { name: '资产', exact: true }).click();
    await page.getByRole('button', { name: '整备清单', exact: true }).click();
    await page.locator('.preparation-cards article').first().getByRole('button', { name: '查看记录' }).click();
    assert.equal(await page.getByRole('button', { name: '确认可配发', exact: true }).isDisabled(), true);
    assert.deepEqual(errors, []);
    console.log('PASS preparations: readiness filter, checklist navigation, confirmation, conflict preservation, CSRF, history, read-only, light/dark 1440/390/320 layouts.');
  } finally { await browser?.close(); await server.httpServer.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
