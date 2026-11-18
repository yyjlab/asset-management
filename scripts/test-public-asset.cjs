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
    for (const width of [1440, 390, 320]) {
      const context = await browser.newContext({ viewport: { width, height: 900 } });
      const page = await context.newPage();
      const errors = [], returns = [], reads = [], unexpected = [];
      page.on('pageerror', error => errors.push(error.message));
      let signedIn = false, allowed = true, detailStatus = 200, returnStatus = 200;
      const user = () => ({ id: 1, username: 'qr-tester', role: 'USER', permissions: allowed ? ['ASSET_RETURN'] : [] });
      const shared = { assetTag: 'TEST-QR-001', name: 'LENOVO ThinkBook 14 G6 IRL', computerModel: 'ThinkBook 14 G6 IRL', cpu: '13th Gen Intel(R) Core(TM) i5-13420H', memory: '15.53 GiB', storage: 'UMIS RPEYJ1T24MKN2QWY 953.87 GiB (NVMe)', graphicsCard: 'Intel(R) Iris(R) Xe Graphics + OrayIddDriver Device',
        assetProfile: 'COMPUTER', category: '笔记本电脑', status: '在用', checkedOut: true, assignedTo: '测试员工', company: null,
        ownershipDepartment: null, location: null, orderNumber: null, imageUrls: ['/api/public/assets/test-token/images/0'],
        boundDisplays: [{ assetTag: 'DISPLAY-001', name: 'AOC显示器', model: 'AOC 27寸 2K', category: '显示器' }, { assetTag: 'DISPLAY-002', name: 'Dell显示器', model: 'P2422H', category: '显示器' }],
        boundComputer: null, relatedDevices: [{ name: '键盘', model: 'K120', serialNumber: null, specification: 'USB有线', quantity: 1 }, { name: '鼠标', model: null, serialNumber: null, specification: '无线', quantity: 2 }],
        knownDefects: [], version: 4, updatedAt: '2026-11-17T09:00:00' };
      const details = { ...shared, assignedTo: '测试员工', location: '仓库', version: 5 };
      await page.route('**/api/**', async route => {
        const req = route.request(), pathname = new URL(req.url()).pathname;
        let status = 200, data;
        if (pathname === '/api/public/assets/test-token') data = shared;
        else if (pathname === '/api/public/assets/test-token/images/0') {
          await route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT1sAAAAASUVORK5CYII=', 'base64') }); return;
        } else if (pathname === '/api/auth/me') { data = signedIn ? user() : { message: '请先登录' }; status = signedIn ? 200 : 401; }
        else if (pathname === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'mock-csrf' };
        else if (pathname === '/api/auth/login') { signedIn = true; data = user(); }
        else if (pathname === '/api/assets/qr/test-token/return-info') { reads.push(pathname); data = detailStatus === 200 ? details : { message: '资产不存在或无权访问' }; status = detailStatus; }
        else if (pathname === '/api/assets/qr/test-token/return') {
          returns.push(req); status = returnStatus;
          data = status === 200 ? { ...details, checkedOut: false, assignedTo: null, status: { name: '当前可用' }, version: 6 } : { message: '资产已被其他人修改，请刷新后重试' };
        } else { unexpected.push(pathname); status = 503; data = { message: 'Outside isolated QR test' }; }
        await route.fulfill({ status, json: data });
      });
      const open = async () => {
        await page.goto(server.resolvedUrls.local[0] + '?qr=test-token');
        await page.getByRole('heading', { name: shared.name, exact: true }).waitFor();
        await page.getByRole('button', { name: '归还资产', exact: true }).waitFor();
      };
      await open();
      assert.equal(await page.getByRole('dialog').count(), 0);
      await page.locator('.public-assignee').getByText('测试员工', { exact: true }).waitFor();
      assert.equal(await page.getByRole('region', { name: '设备已知缺陷', exact: true }).count(), 0);
      assert.equal(await page.getByText('暂无已知缺陷', { exact: true }).count(), 0);
      assert.equal(await page.locator('.public-action-panel').count(), 0);
      assert.equal(await page.getByText('MOBILE RETURN', { exact: true }).count(), 0);
      const bindings = page.locator('.public-related-section').filter({ has: page.getByRole('heading', { name: '设备绑定' }) });
      assert.equal(await bindings.locator('.public-related-item').count(), 2);
      await bindings.getByText('DISPLAY-001', { exact: true }).waitFor();
      await bindings.getByText('AOC 27寸 2K', { exact: true }).waitFor();
      const accessories = page.locator('.public-accessory-section');
      await accessories.getByText('键盘', { exact: true }).waitFor();
      await accessories.getByText('鼠标', { exact: true }).waitFor();
      await accessories.getByText('×2', { exact: true }).waitFor();
      assert.equal(await accessories.getByText('序列号', { exact: true }).count(), 0);
      for (const theme of ['light', 'dark']) {
        await page.evaluate(value => document.documentElement.dataset.theme = value, theme);
        assert.equal(await page.locator('.public-asset-card').evaluate(node => node.scrollWidth <= node.clientWidth + 1), true);
        const fields = page.locator('.public-asset-fields');
        for (const text of [shared.cpu, shared.memory, shared.storage, shared.graphicsCard]) await fields.getByText(text, { exact: true }).waitFor();
        const cells = await fields.locator(':scope > div').evaluateAll(nodes => nodes.map(node => { const r = node.getBoundingClientRect(); return { x: r.x, y: r.y, bottom: r.bottom }; }));
        assert.ok(cells.every((cell, index) => !index || Math.abs(cell.x - cells[0].x) < 1 && cell.y >= cells[index - 1].bottom - 1), 'configuration must stay in one column');
      }
      if (process.argv.includes('--preview') && width === 390) console.log('SCREENSHOT:' + (await page.screenshot({ type: 'jpeg', quality: 65, fullPage: true })).toString('base64'));
      assert.equal(reads.length, 0); assert.equal(returns.length, 0);
      assert.equal(await page.locator('.public-asset-gallery img').evaluate(image => image.complete && image.naturalWidth > 0), true);
      assert.equal(await page.locator('.public-asset-card').evaluate(node => node.scrollWidth <= node.clientWidth + 1), true);
      await page.getByRole('button', { name: '归还资产', exact: true }).click();
      await page.getByRole('heading', { name: '登录后继续归还' }).waitFor();
      assert.equal(returns.length, 0); assert.equal(reads.length, 0);
      await page.getByLabel('账号', { exact: true }).fill('qr-tester');
      await page.getByLabel('密码', { exact: true }).fill('test-not-a-secret');
      await page.getByRole('button', { name: /^登录并继续/ }).click();
      const confirm = page.getByRole('alertdialog'); await confirm.waitFor();
      await confirm.getByText('测试员工', { exact: true }).waitFor(); assert.equal(returns.length, 0);
      await confirm.getByRole('button', { name: '取消', exact: true }).click(); assert.equal(returns.length, 0);
      await page.getByRole('button', { name: '归还资产', exact: true }).click();
      returnStatus = 409;
      await page.getByRole('button', { name: '确认归还', exact: true }).click();
      await page.getByText('资产已被其他人修改，请刷新后重试', { exact: true }).waitFor();
      assert.equal(returns[0].headers()['if-match'], '5');
      assert.equal(returns[0].headers()['x-csrf-token'], 'mock-csrf');
      returnStatus = 200;
      await page.getByRole('button', { name: '确认归还', exact: true }).click();
      await page.getByText('资产已经归还，领用人已清空，状态：当前可用', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: '归还资产', exact: true }).count(), 0);
      await page.locator('.public-assignee').getByText('暂未领用', { exact: true }).waitFor();
      const count = returns.length;
      allowed = false; await open();
      await page.getByRole('button', { name: '归还资产', exact: true }).click();
      await page.getByText('当前登录账号没有资产归还权限', { exact: true }).waitFor();
      assert.equal(returns.length, count);
      allowed = true; detailStatus = 404; await open();
      await page.getByRole('button', { name: '归还资产', exact: true }).click();
      await page.getByText('资产不存在或无权访问', { exact: true }).waitFor();
      assert.equal(await page.getByRole('alertdialog').count(), 0); assert.equal(returns.length, count);
      shared.assignedTo = '<img src=x onerror=alert(1)>这是一个较长的员工显示姓名'; await open();
      await page.locator('.public-assignee').getByText(shared.assignedTo, { exact: true }).waitFor();
      assert.equal(await page.locator('.public-assignee img').count(), 0);
      assert.equal(await page.locator('.public-asset-card').evaluate(node => node.scrollWidth <= node.clientWidth + 1), true);
      shared.assignedTo = '   '; await open();
      await page.locator('.public-assignee').getByText('领用人待补充', { exact: true }).waitFor();
      shared.checkedOut = false; await page.reload();
      await page.locator('.public-assignee').getByText('暂未领用', { exact: true }).waitFor();
      assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
      console.log(`PASS QR ${width}px: holder and attached equipment, raw single-column configuration, light/dark layout, privacy, login, permissions, scope, confirmation, cancellation, version, CSRF, conflict and successful return.`);
      await context.close();
    }
  } finally { await browser?.close(); await server.httpServer.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
