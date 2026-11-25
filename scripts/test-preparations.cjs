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
    const loaned = { ...assets[0], id: 13, assetTag: '2026112513', name: '电脑13', checkedOut: true, assignedTo: '领用员工', status: { id: 6, type: 'STATUS', name: '已经领出' } };
    assets.push(loaned);
    records.push({ assetId: 13, version: -1, assetVersion: 0, state: 'CHECKED_OUT', reason: '设备仍在领用中', previousHolder: null, updatedAt: null });
    const maintenance = { id: 7, type: 'STATUS', name: '维修中' };
    const scrapped = { id: 8, type: 'STATUS', name: '已报废' };
    assets.push(
      { ...assets[0], id: 14, assetTag: '2026112514', name: '维护电脑', status: maintenance },
      { ...assets[0], id: 15, assetTag: '2026112515', name: '已报废显示器', status: scrapped },
    );
    records.push({ assetId: 14, version: -1, assetVersion: 0, state: 'BLOCKED', reason: '尚无整备检查记录', previousHolder: null, updatedAt: null });
    let checks = Object.fromEntries(['DATA', 'SYSTEM', 'DOMAIN', 'HARDWARE'].map(key => [key, { result: 'UNCHECKED', note: '' }]));
    let events = [], posts = 0, rejectNext = false, readOnly = false;
    await page.route('**/api/**', async route => {
      const request = route.request(), pathname = new URL(request.url()).pathname;
      let data = [];
      if (pathname === '/api/auth/me') data = { id: 1, username: 'admin', displayName: 'admin', role: 'SUPER_ADMIN', permissions: readOnly ? ['ASSET_VIEW'] : ['ASSET_VIEW', 'ASSET_EDIT'], canManageUsers: true, dataScope: 'ALL', departments: [], hasAvatar: false };
      else if (pathname === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' };
      else if (pathname === '/api/dashboard') data = null;
      else if (pathname === '/api/assets/summary') data = { total: assets.length, available: loaned.checkedOut ? 3 : 4, checkedOut: loaned.checkedOut ? 1 : 0, maintenance: 1, scrapped: 1 };
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
    assert.equal(await page.locator('.asset-name').filter({ hasText: /^电脑13$/ }).count(), 1);
    await page.getByLabel('筛选配发状态').selectOption('READY'); assert.equal(await page.locator('.asset-name').count(), 1);
    assert.equal(await page.locator('.asset-name').innerText(), '电脑12');
    await page.getByRole('button', { name: '整备清单', exact: true }).click();
    const deviceRows = page.locator('.preparation-list-row:not(.preparation-columns)');
    const pendingTab = page.getByRole('button', { name: /^待处理/ });
    await deviceRows.first().waitFor();
    assert.equal(await pendingTab.getAttribute('aria-pressed'), 'true');
    assert.equal(await deviceRows.count(), 3);
    assert.match(await deviceRows.first().innerText(), /电脑11/);
    assert.match(await deviceRows.filter({ hasText: '维护电脑' }).innerText(), /设备处于维护中/);
    assert.equal(await deviceRows.filter({ hasText: '尚无整备检查记录' }).count(), 0);
    await page.getByRole('button', { name: /^可配发/ }).click();
    assert.match(await deviceRows.innerText(), /电脑12/);
    await page.getByRole('button', { name: /^已报废/ }).click();
    assert.match(await deviceRows.innerText(), /已报废显示器/);
    await pendingTab.click();
    for (const theme of ['light', 'dark']) for (const width of [1440, 768, 390, 320]) {
      await page.evaluate(value => document.documentElement.dataset.theme = value, theme);
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await page.locator('.preparation-page').evaluate(e => e.scrollWidth <= e.clientWidth + 1), true, `list fits ${theme}/${width}`);
      assert.equal(await deviceRows.first().getByRole('button', { name: '开始检查', exact: true }).isVisible(), true);
      if (width <= 768) await page.waitForFunction(() => document.querySelector('.asset-nav-parent')?.getAttribute('aria-expanded') === 'false');
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    const search = page.getByLabel('搜索设备或原领用人');
    await search.fill(loaned.assetTag);
    await page.getByText('暂无符合条件的设备', { exact: true }).waitFor();
    Object.assign(loaned, { checkedOut: false, status, assignedTo: null, version: 1 });
    records = records.map(r => r.assetId === loaned.id ? { ...r, assetVersion: 1, version: 0, state: 'PENDING', reason: '归还后尚未整备', previousHolder: '领用员工' } : r);
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await deviceRows.filter({ hasText: '电脑13' }).waitFor();
    await page.getByRole('button', { name: '清除搜索', exact: true }).click();
    await page.getByLabel('筛选待处理状态').selectOption('PENDING');
    assert.equal(await deviceRows.count(), 2);
    await deviceRows.filter({ hasText: '电脑11' }).getByRole('button', { name: '开始检查' }).click();
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
    await deviceRows.first().getByRole('button', { name: '查看记录' }).click();
    assert.equal(await page.getByRole('button', { name: '确认可配发', exact: true }).isDisabled(), true);
    assert.deepEqual(errors, []);
    console.log('PASS preparations: hide checked-out assets until return, readiness filter, navigation, confirmation, conflict preservation, CSRF, history, read-only, light/dark 1440/390/320 layouts.');
  } finally { await browser?.close(); await server.httpServer.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
