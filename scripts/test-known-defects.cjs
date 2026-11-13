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
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const company = { id: 1, type: 'COMPANY', name: '微丰启' }, location = { id: 2, type: 'LOCATION', name: '公司' };
    const model = { id: 3, type: 'MODEL', name: 'ThinkPad L13' }, category = { id: 4, type: 'CATEGORY', name: '笔记本电脑', assetProfile: 'COMPUTER' };
    const status = { id: 5, type: 'STATUS', name: '当前可用' };
    const defects = [{ id: 'a', description: 'P键失灵', recordedBy: 'admin', recordedAt: '2026-11-13T09:00:00', archivedAt: null },
      { id: 'b', description: 'Type-C不能充电', recordedBy: 'admin', recordedAt: '2026-11-13T09:00:00', archivedAt: null },
      { id: 'c', description: '历史记录', recordedBy: 'admin', recordedAt: '2026-11-12T09:00:00', archivedBy: 'admin', archivedAt: '2026-11-13T09:00:00' }];
    let asset = { id: 10, version: 0, assetTag: '202611130001', name: '测试电脑', company, location, model, category, status,
      checkedOut: false, assignedTo: null, relatedDevices: [], accessories: [], imageUrls: [], customParameters: [], boundDisplays: [],
      notes: '保修说明', domainJoinStatus: 'JOINED', knownDefects: defects, createdAt: '2026-11-13T09:00:00', updatedAt: '2026-11-13T09:00:00' };
    let saved;
    await page.route('**/api/**', async route => {
      const request = route.request(), pathname = new URL(request.url()).pathname;
      let data = [];
      if (pathname === '/api/auth/me') data = { id: 1, username: 'admin', displayName: 'admin', role: 'SUPER_ADMIN', permissions: ['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CREATE'], canManageUsers: true, dataScope: 'ALL', departments: [], hasAvatar: false, dingtalkLogin: false };
      else if (pathname === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' };
      else if (pathname === '/api/dashboard') data = null;
      else if (pathname === '/api/assets/summary') data = { total: 2, available: 2, checkedOut: 0, maintenance: 0, scrapped: 0 };
      else if (pathname === '/api/assets') data = [asset, { ...asset, id: 11, name: '正常电脑', knownDefects: [] }];
      else if (pathname === '/api/lookups') data = [company, location, model, category, status];
      else if (pathname === '/api/assets/10' && request.method() === 'PUT') {
        saved = request.postDataJSON();
        assert.equal(request.headers()['if-match'], '0');
        asset = { ...asset, ...saved, version: 1, knownDefects: saved.knownDefects.map((item, index) => ({ ...item, id: item.id || `new-${index}`, recordedBy: 'admin', recordedAt: '2026-11-13T09:00:00', archivedAt: item.archived ? '2026-11-13T09:01:00' : null })) };
        data = asset;
      } else if (pathname === '/api/assets/10') data = asset;
      else if (!pathname.includes('domain-statuses') && pathname !== '/api/assignees' && !pathname.startsWith('/api/accessory-stock') && !pathname.startsWith('/api/foundation/')) {
        await route.fulfill({ status: 503, json: { message: 'Outside isolated defect test' } }); return;
      }
      await route.fulfill({ json: data });
    });
    await page.goto(server.resolvedUrls.local[0]);
    await page.getByRole('button', { name: '资产', exact: true }).click();
    await page.getByRole('button', { name: '全部资产', exact: true }).click();
    await page.locator('.asset-name').filter({ hasText: '测试电脑' }).waitFor();
    assert.equal(await page.locator('.known-defect-badge').innerText(), '已知缺陷 2');
    await page.getByLabel('有已知缺陷').check();
    assert.equal(await page.locator('.asset-name').count(), 1);
    await page.locator('.asset-name').click();
    const details = page.getByRole('region', { name: '设备已知缺陷', exact: true });
    await details.getByText('P键失灵', { exact: true }).waitFor();
    assert.equal(await details.locator('.known-defect-list li').count(), 2);
    assert.ok((await page.locator('.record-notes').boundingBox()).height < 110, 'notes must remain compact');
    for (const theme of ['light', 'dark']) for (const width of [1440, 390]) {
      await page.evaluate(value => document.documentElement.dataset.theme = value, theme);
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await details.evaluate(element => element.scrollWidth <= element.clientWidth + 1), true, 'defect details must fit');
      if (process.argv.includes('--preview') && width === 1440 && theme === 'light') console.log('SCREENSHOT:' + (await details.screenshot({ type: 'jpeg', quality: 60 })).toString('base64'));
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await details.getByRole('button', { name: '编辑记录', exact: true }).click();
    const editor = page.getByRole('region', { name: '设备已知缺陷', exact: true });
    await editor.getByLabel('缺陷描述 1', { exact: true }).fill('P键偶发失灵');
    await editor.getByRole('button', { name: '移入历史', exact: true }).first().click();
    await editor.getByRole('button', { name: '添加缺陷', exact: true }).click();
    await editor.getByLabel('缺陷描述 2', { exact: true }).fill('耳机接口接触不良');
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await editor.evaluate(element => element.scrollWidth <= element.clientWidth + 1), true, 'editor must fit');
    }
    await page.getByRole('button', { name: '保存修改', exact: true }).click();
    await page.locator('.symmetric-asset-form').waitFor({ state: 'hidden' });
    assert.equal(saved.knownDefects.length, 4);
    assert.equal(saved.knownDefects[0].archived, true);
    assert.equal(saved.knownDefects[0].description, 'P键偶发失灵');
    assert.equal(saved.knownDefects[3].description, '耳机接口接触不良');
    assert.equal(saved.notes, '保修说明');
    assert.equal(saved.checkedOut, false);
    assert.equal(saved.domainJoinStatus, 'JOINED');
    await page.getByRole('button', { name: '克隆资产', exact: true }).click();
    assert.equal(await page.locator('.known-defect-edit-row').count(), 0, 'clone must not inherit physical defects');
    assert.deepEqual(errors, []);
    console.log('PASS known defects: badges, filter, detail, compact notes, archive, add, save, clone isolation, light/dark desktop/mobile.');
  } finally { await browser?.close(); await server.httpServer.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
