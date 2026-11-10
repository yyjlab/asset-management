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
    const page = await browser.newPage();
    page.setDefaultTimeout(10000);
    const errors = [], decisions = [];
    page.on('pageerror', error => errors.push(error.message));
    const row = { externalRegistrationId: 'ad:test:device:1', receivedAt: '2026-11-10T08:00:00Z', capturedAt: '2026-11-10T07:50:00Z',
      declaredOperator: { id: 'agent', displayName: 'PE 自动采集' }, credentialId: 'service-1', state: 'pending_review', revision: 0,
      model: null, cpu: 'Intel Core i5', memory: '16 GiB', storage: 'NVMe 512 GiB', graphicsCard: null,
      manufacturerSerialNumber: 'SN-TEST', categoryId: null, asset: null, reviewedAt: null, reviewedBy: null, reviewNote: null };
    let failApproval = false, administrator = true, intakeReads = 0;
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname;
      let data;
      if (endpoint === '/api/auth/me') data = { id: 1, username: 'admin', displayName: 'Test Admin', role: administrator ? 'SUPER_ADMIN' : 'USER', permissions: ['ASSET_VIEW'], canManageUsers: administrator, dataScope: 'ALL', departments: [], hasAvatar: false, dingtalkLogin: false };
      else if (endpoint === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'isolated-test-csrf' };
      else if (endpoint.endsWith('/qr')) data = { qrBaseUrl: 'https://example.test' };
      else if (endpoint.endsWith('/ad-deployment/status')) data = { enabled: true, allowHttp: true, secureRequest: false, endpoint: '/api/integrations/ad-deployment/v1/intakes', lastReceivedAt: row.receivedAt };
      else if (endpoint.endsWith('/ad-deployment/credentials')) data = [];
      else if (endpoint.endsWith('/ad-deployment/registrations')) data = { items: [], page: 0, totalPages: 0, totalElements: 0 };
      else if (endpoint.endsWith('/ad-deployment/intake-options')) data = { categories: [{ id: 12, name: '笔记本电脑' }] };
      else if (endpoint.endsWith('/ad-deployment/intakes')) { intakeReads++; data = { items: url.searchParams.get('state') === row.state ? [row] : [], page: 0, totalPages: 1, totalElements: url.searchParams.get('state') === row.state ? 1 : 0 }; }
      else if (endpoint.endsWith('/approve') || endpoint.endsWith('/reject')) {
        assert.equal(request.headers()['x-csrf-token'], 'isolated-test-csrf');
        const body = request.postDataJSON();
        decisions.push({ endpoint, body });
        assert.equal(body.revision, 0);
        if (failApproval && endpoint.endsWith('/approve')) { await route.fulfill({ status: 409, json: { message: '状态已变化，请刷新后核对' } }); return; }
        row.state = endpoint.endsWith('/approve') ? 'approved' : 'rejected';
        row.reviewNote = body.note;
        row.reviewedBy = 'admin'; row.reviewedAt = row.receivedAt;
        if (row.state === 'approved') { Object.assign(row, body); row.asset = { id: 81, assetTag: '202611100001', version: 0 }; }
        data = row;
      } else if (['/api/assets', '/api/lookups', '/api/assignees'].includes(endpoint)) data = [];
      else if (endpoint === '/api/assets/summary') data = { total: 0, available: 0, checkedOut: 0, maintenance: 0, scrapped: 0 };
      else { await route.fulfill({ status: 503, json: { message: 'Outside isolated intake test' } }); return; }
      await route.fulfill({ json: data });
    });
    await page.goto(server.resolvedUrls.local[0]);
    await page.getByRole('button', { name: '装机电脑审核', exact: true }).click();
    const queue = page.getByRole('region', { name: '装机电脑审核' });
    assert.equal(new URL(page.url()).searchParams.get('view'), 'deployment-review');
    assert.equal(await page.locator('.sidebar nav .nav-item').last().getAttribute('aria-label'), '装机电脑审核');
    await queue.getByRole('button', { name: '核对 / 审核' }).waitFor();
    await page.reload();
    await queue.getByRole('button', { name: '核对 / 审核' }).waitFor();
    await page.evaluate(() => history.replaceState(null, '', location.href));
    await page.reload();
    await queue.getByRole('button', { name: '核对 / 审核' }).waitFor();
    await page.getByRole('button', { name: '设置', exact: true }).click();
    assert.equal(new URL(page.url()).searchParams.has('view'), false);
    await page.getByRole('button', { name: '装机平台接入', exact: true }).click();
    assert.equal(await queue.count(), 0, 'integration settings must not duplicate the review list');
    await page.goBack();
    await queue.getByRole('button', { name: '核对 / 审核' }).waitFor();
    await queue.getByRole('button', { name: '核对 / 审核' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('资产名称', { exact: true }).fill('测试笔记本');
    await dialog.getByLabel('设备型号 *', { exact: true }).fill('ThinkPad T14');
    await dialog.getByLabel('资产分类', { exact: true }).selectOption('12');
    for (const theme of ['light', 'dark']) {
      await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme);
      for (const size of [{ width: 1440, height: 900 }, { width: 2544, height: 963 }, { width: 390, height: 844 }]) {
        await page.setViewportSize(size);
        const layout = await queue.evaluate(element => {
          const content = element.closest('.content');
          return { right: element.getBoundingClientRect().right, edge: content.getBoundingClientRect().right - parseFloat(getComputedStyle(content).paddingRight),
            width: element.getBoundingClientRect().width, scrollWidth: document.documentElement.scrollWidth, viewport: innerWidth };
        });
        assert.ok(Math.abs(layout.right - layout.edge) <= 2, 'review table must fill the available content width');
        assert.ok(layout.scrollWidth <= layout.viewport, 'full width table must not overflow the page');
        if (size.width === 2544) assert.ok(layout.width > 2000, 'ultrawide review must not inherit the settings 1200px cap');
        const geometry = await dialog.evaluate(element => ({ width: element.clientWidth, scrollWidth: element.scrollWidth, rect: element.getBoundingClientRect().toJSON() }));
        assert.ok(geometry.scrollWidth <= geometry.width, 'review content must not overflow horizontally');
        assert.ok(geometry.rect.height <= size.height, 'review dialog must stay in viewport');
        assert.ok(geometry.rect.left >= 0 && geometry.rect.right <= size.width, 'review dialog must fit viewport width');
      }
    }
    failApproval = true;
    await dialog.getByRole('button', { name: '批准并创建资产', exact: true }).click();
    await dialog.getByRole('alert').waitFor();
    assert.equal(await dialog.getByLabel('设备型号 *', { exact: true }).inputValue(), 'ThinkPad T14');
    failApproval = false;
    await dialog.getByRole('button', { name: '批准并创建资产', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert.equal(decisions.length, 2);
    assert.equal(decisions[1].body.categoryId, 12);
    assert.equal(decisions[1].body.graphicsCard, null);
    assert.ok(!('assetTag' in decisions[1].body), 'browser must not invent an asset tag');
    await queue.getByRole('button', { name: '已批准', exact: true }).click();
    await queue.getByText('202611100001', { exact: true }).waitFor();
    await queue.getByRole('button', { name: '查看结果' }).click();
    assert.equal(await dialog.getByRole('button', { name: '批准并创建资产', exact: true }).count(), 0);
    await dialog.getByRole('button', { name: '关闭', exact: true }).click();
    row.state = 'pending_review'; row.asset = null;
    await queue.getByRole('button', { name: '待审核', exact: true }).click();
    await queue.getByRole('button', { name: '核对 / 审核' }).click();
    await dialog.getByRole('button', { name: '不批准', exact: true }).click();
    await dialog.getByLabel('不批准原因 *', { exact: true }).fill('重复送审，保留现有资产');
    await dialog.getByRole('button', { name: '确认不批准', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert.equal(decisions.at(-1).body.note, '重复送审，保留现有资产');
    assert.ok(decisions.at(-1).endpoint.endsWith('/reject'));
    assert.equal(row.asset, null);
    administrator = false;
    const before = intakeReads;
    await page.reload();
    await page.getByText('当前账号尚未获得此功能的访问权限，请联系管理员授权。', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '装机电脑审核', exact: true }).count(), 0);
    assert.equal(intakeReads, before, 'unauthorized deep links must not mount review API requests');
    assert.deepEqual(errors, []);
    console.log('PASS intake review: standalone menu, reload, direct URL, back navigation, access guard, corrections, auto tag, rejection, CSRF and desktop/mobile layout');
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.httpServer.close(resolve));
  }
}
main().then(() => process.exit(0)).catch(error => { console.error(error); process.exit(1); });
