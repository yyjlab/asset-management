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
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**', async route => {
      const endpoint = new URL(route.request().url()).pathname;
      let data;
      if (endpoint === '/api/auth/me') data = { id: 1, username: 'admin', displayName: 'Test Admin', role: 'SUPER_ADMIN', permissions: ['ASSET_VIEW'], canManageUsers: true, dataScope: 'ALL', departments: [], hasAvatar: false, dingtalkLogin: false };
      else if (endpoint.endsWith('/qr')) data = { qrBaseUrl: 'https://example.test' };
      else if (endpoint.endsWith('/ad-deployment/status')) data = { enabled: false, allowHttp: false, secureRequest: false, endpoint: '/api/integrations/ad-deployment/v1/registrations', lastReceivedAt: null };
      else if (endpoint.endsWith('/ad-deployment/credentials')) data = [];
      else if (endpoint.endsWith('/ad-deployment/registrations')) data = { items: [], page: 0, totalPages: 0, totalElements: 0 };
      else if (['/api/assets', '/api/lookups', '/api/assignees'].includes(endpoint)) data = [];
      else if (endpoint === '/api/assets/summary') data = { total: 0, available: 0, checkedOut: 0, maintenance: 0, scrapped: 0 };
      else { await route.fulfill({ status: 503, json: { message: 'Outside isolated layout test' } }); return; }
      await route.fulfill({ json: data });
    });
    await page.goto(server.resolvedUrls.local[0]);
    await page.getByRole('button', { name: '设置', exact: true }).click();
    await page.getByRole('button', { name: '装机平台接入', exact: true }).click();
    await page.getByRole('button', { name: '接入设置', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('启用装机平台接入', { exact: true }).check();
    await dialog.getByLabel('连接方式', { exact: true }).selectOption('http');

    async function assertLayout() {
      const geometry = await dialog.evaluate(element => ({
        width: element.clientWidth, scrollWidth: element.scrollWidth,
        height: element.getBoundingClientRect().height,
        checks: [...element.querySelectorAll('.deployment-check')].map(label => {
          const box = label.querySelector('input').getBoundingClientRect();
          const text = label.querySelector('span').getBoundingClientRect();
          const bounds = label.getBoundingClientRect();
          return { boxWidth: box.width, boxHeight: box.height, textWidth: text.width, textRight: text.right, right: bounds.right, textHeight: text.height };
        }),
      }));
      assert.ok(geometry.scrollWidth <= geometry.width, 'dialog content must not scroll horizontally');
      assert.ok(geometry.height < 650, 'dialog must not grow into a tall checkbox column');
      for (const row of geometry.checks) {
        assert.ok(row.boxWidth >= 16 && row.boxWidth <= 20, 'checkbox must stay compact');
        assert.ok(row.boxHeight >= 16 && row.boxHeight <= 20, 'checkbox height must stay compact');
        assert.ok(row.textWidth > 150, 'label text must retain usable line width');
        assert.ok(row.textRight <= row.right + 1, 'label text must stay inside its row');
        assert.ok(row.textHeight < 150, 'label must wrap naturally, not one character per line');
      }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    }

    for (const theme of ['light', 'dark']) {
      await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme);
      for (const viewport of [{ width: 1440, height: 900 }, { width: 1920, height: 1080 }, { width: 390, height: 844 }]) {
        await page.setViewportSize(viewport);
        await assertLayout();
        console.log(`PASS ${theme} ${viewport.width}x${viewport.height}: checkbox, label width, dialog height, overflow`);
      }
    }
    // Prove these assertions detect the original inherited full-width checkbox bug.
    const brokenStyle = await page.addStyleTag({ content: '.deployment-check input[type="checkbox"] { width: 100% !important; max-width: none !important; min-width: 0 !important; height: 41px !important; flex-basis: auto !important; }' });
    await assert.rejects(assertLayout);
    await brokenStyle.evaluate(element => element.remove());
    await assertLayout();
    assert.ok(await dialog.getByRole('button', { name: '保存设置', exact: true }).isDisabled());
    await dialog.getByRole('checkbox').last().check();
    assert.ok(await dialog.getByRole('button', { name: '保存设置', exact: true }).isEnabled());
    await page.getByRole('button', { name: '关闭接入设置', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert.deepEqual(errors, []);
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.httpServer.close(resolve));
  }
}

main().then(() => process.exit(0)).catch(error => { console.error(error); process.exit(1); });
