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
    for (const width of [1440, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 900 } });
      const page = await context.newPage();
      const errors = [], returns = [], reads = [], unexpected = [];
      page.on('pageerror', error => errors.push(error.message));
      let signedIn = false, allowed = true, detailStatus = 200, returnStatus = 200;
      const user = () => ({ id: 1, username: 'qr-tester', role: 'USER', permissions: allowed ? ['ASSET_RETURN'] : [] });
      const shared = { assetTag: 'TEST-QR-001', name: '测试笔记本', computerModel: 'ThinkPad L13', cpu: 'i5', memory: '16G', storage: '512G',
        assetProfile: 'COMPUTER', category: '笔记本电脑', status: '在用', checkedOut: true, assignedTo: null, company: null,
        ownershipDepartment: null, location: null, orderNumber: null, imageUrls: ['/api/public/assets/test-token/images/0'],
        boundDisplays: [], boundComputer: null, relatedDevices: [], knownDefects: [], version: 4, updatedAt: '2026-11-17T09:00:00' };
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
        await page.getByRole('heading', { name: '测试笔记本', exact: true }).waitFor();
        await page.getByRole('button', { name: '归还资产', exact: true }).waitFor();
      };
      await open();
      assert.equal(await page.getByRole('dialog').count(), 0);
      assert.equal(await page.getByText('测试员工', { exact: true }).count(), 0);
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
      const count = returns.length;
      allowed = false; await open();
      await page.getByRole('button', { name: '归还资产', exact: true }).click();
      await page.getByText('当前登录账号没有资产归还权限', { exact: true }).waitFor();
      assert.equal(returns.length, count);
      allowed = true; detailStatus = 404; await open();
      await page.getByRole('button', { name: '归还资产', exact: true }).click();
      await page.getByText('资产不存在或无权访问', { exact: true }).waitFor();
      assert.equal(await page.getByRole('alertdialog').count(), 0); assert.equal(returns.length, count);
      assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
      console.log(`PASS QR ${width}px: anonymous details/photo, private-field isolation, login, permissions, scope, confirmation, cancellation, version, CSRF, conflict and successful return.`);
      await context.close();
    }
  } finally { await browser?.close(); await server.httpServer.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
