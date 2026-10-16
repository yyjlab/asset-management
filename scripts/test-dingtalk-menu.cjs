const assert = require('node:assert/strict');
const { before, test } = require('node:test');
const { createRequire } = require('node:module');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const vm = require('node:vm');

const frontend = path.resolve(__dirname, '../frontend');
const frontendRequire = createRequire(path.join(frontend, 'package.json'));
const React = frontendRequire('react');
const { renderToStaticMarkup } = frontendRequire('react-dom/server');
let compiled;

before(async () => {
  const { build } = await import(pathToFileURL(frontendRequire.resolve('vite')).href);
  // Reuse Vite's TSX compiler entirely in memory; no test framework or build files are added.
  compiled = Object.fromEntries(await Promise.all(['App.tsx', 'api.ts', 'components/HandoverPage.tsx', 'components/SelfServicePage.tsx', 'components/Sidebar.tsx', 'components/Icons.tsx'].map(async file => {
    const result = await build({
      root: frontend, configFile: false, logLevel: 'silent',
      build: {
        ssr: true, write: false, minify: false,
        rollupOptions: {
          input: path.join(frontend, 'src', file),
          external: (_id, importer) => Boolean(importer),
          output: { format: 'cjs' },
        },
      },
    });
    return [file, result.output.find(item => item.type === 'chunk').code];
  })));
});

function load(file, mocks = {}, collapsed = false, globals = {}) {
  const module = { exports: {} };
  const browser = { location: { pathname: '/' }, localStorage: { getItem: () => String(collapsed) } };
  vm.runInNewContext(compiled[file], {
    module, exports: module.exports, window: browser, URLSearchParams, crypto: require('node:crypto').webcrypto, ...globals,
    require: id => Object.hasOwn(mocks, id) ? mocks[id] : frontendRequire(id),
  }, { filename: file });
  return module.exports;
}

function sidebarMarkup(overrides = {}, collapsed = false) {
  const icons = load('components/Icons.tsx');
  const { Sidebar } = load('components/Sidebar.tsx', { './Icons': icons }, collapsed);
  return renderToStaticMarkup(React.createElement(Sidebar, {
    section: 'dashboard', canManageUsers: false, dingtalkLogin: false,
    permissions: ['ASSET_VIEW'], onFilter() {}, onSection() {}, ...overrides,
  }));
}

function menuLabels(markup) {
  return [...markup.matchAll(/class="nav-item[^>]*>.*?<span>(.*?)<\/span>/g)].map(match => match[1]);
}

test('DingTalk employees see all non-settings menus including handovers', () => {
  assert.deepEqual(menuLabels(sidebarMarkup({ dingtalkLogin: true, permissions: [] })), ['驾驶舱', '员工自助', '资产', '入离职交接', '用户', '日志', '数据']);
});

test('collapsed DingTalk menus retain their labels and accessible names', () => {
  const markup = sidebarMarkup({ dingtalkLogin: true }, true);
  assert.match(markup, /sidebar collapsed/);
  for (const label of ['驾驶舱', '员工自助', '资产', '入离职交接', '用户', '日志', '数据']) assert.ok(markup.includes(`aria-label="${label}"`));
  assert.ok(!markup.includes('aria-label="设置"'));
});

test('password login retains existing permission-based menus', () => {
  assert.deepEqual(menuLabels(sidebarMarkup()), ['驾驶舱', '员工自助', '资产', '入离职交接']);
  assert.deepEqual(menuLabels(sidebarMarkup({ permissions: [] })), ['驾驶舱', '员工自助', '入离职交接']);
  assert.deepEqual(menuLabels(sidebarMarkup({ permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '员工自助', '资产', '入离职交接', '数据']);
});

test('administrator keeps the settings menu with either login method', () => {
  for (const dingtalkLogin of [true, false]) {
    assert.deepEqual(menuLabels(sidebarMarkup({ canManageUsers: true, dingtalkLogin, permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '员工自助', '资产', '入离职交接', '用户', '日志', '设置', '数据']);
  }
});

test('every visible DingTalk menu navigates to its own section', () => {
  const sections = [];
  const filters = [];
  const icons = load('components/Icons.tsx');
  const { Sidebar } = load('components/Sidebar.tsx', {
    './Icons': icons, react: { useState: () => [false, () => {}] },
  });
  const tree = Sidebar({
    section: 'dashboard', canManageUsers: false, dingtalkLogin: true, permissions: [],
    onSection: value => sections.push(value), onFilter: value => filters.push(value),
  });
  const nav = tree.props.children.find(child => child.type === 'nav');
  for (const button of nav.props.children.filter(Boolean)) button.props.onClick();
  assert.deepEqual(sections, ['dashboard', 'selfService', 'assets', 'handover', 'users', 'logs', 'transfer']);
  assert.deepEqual(filters, ['all']);
});

function appMarkup(section, overrides = {}) {
  const user = {
    id: 1, username: 'employee', displayName: '员工', role: 'USER',
    canManageUsers: false, dingtalkLogin: true, hasAvatar: false,
    permissions: ['ASSET_VIEW'], dataScope: 'SELF', departments: [], ...overrides,
  };
  let stateIndex = 0;
  const react = { ...React, useState(initial) {
    const index = stateIndex++;
    return React.useState(index === 1 ? user : index === 2 ? false : index === 5 ? section : initial);
  } };
  const icons = load('components/Icons.tsx');
  const sidebar = load('components/Sidebar.tsx', { './Icons': icons });
  const rendered = [];
  const componentNames = ['AssetDetail', 'AssetForm', 'AssetTable', 'LookupModal', 'LoginPage', 'UserManagement', 'SystemSettings', 'ConfirmDialog', 'AuditLogPage', 'AvatarEditor', 'AssetLabelPrintPreview', 'ComputerDashboard', 'AssetTransfer', 'HandoverPage', 'SelfServicePage'];
  const mocks = { react, './components/Icons': icons, './components/Sidebar': sidebar, './api': { api: {}, ApiError: class extends Error {} } };
  for (const name of componentNames) mocks[`./components/${name}`] = { [name]: () => {
    rendered.push(name);
    return React.createElement('div', null, name);
  } };
  const loaded = load('App.tsx', mocks);
  const App = loaded.default || loaded;
  return { markup: renderToStaticMarkup(React.createElement(App)), rendered };
}

for (const section of ['users', 'logs', 'transfer', 'settings']) {
  test(`unauthorized ${section} shows an access notice without mounting its data component`, () => {
    const { markup, rendered } = appMarkup(section);
    assert.match(markup, /当前账号尚未获得此功能的访问权限/);
    assert.deepEqual(rendered, []);
  });
}

test('asset menu without view permission shows a notice instead of the dashboard', () => {
  const { markup, rendered } = appMarkup('assets', { permissions: [] });
  assert.match(markup, /当前账号尚未获得此功能的访问权限/);
  assert.deepEqual(rendered, []);
});

test('import permission alone cannot open an empty data page', () => {
  assert.match(appMarkup('transfer', { permissions: ['ASSET_IMPORT'] }).markup, /当前账号尚未获得此功能的访问权限/);
});

test('authorized export and administrator pages still render their existing components', () => {
  assert.deepEqual(appMarkup('transfer', { permissions: ['ASSET_EXPORT'] }).rendered, ['AssetTransfer']);
  assert.deepEqual(appMarkup('users', { canManageUsers: true }).rendered, ['UserManagement']);
  assert.deepEqual(appMarkup('logs', { canManageUsers: true }).rendered, ['AuditLogPage']);
  assert.deepEqual(appMarkup('settings', { canManageUsers: true }).rendered, ['SystemSettings']);
});

test('DingTalk employees keep their existing asset page and dashboard', () => {
  assert.deepEqual(appMarkup('assets').rendered, ['AssetTable', 'LookupModal']);
  assert.deepEqual(appMarkup('dashboard').rendered, ['ComputerDashboard']);
});

test('handover menu opens the handover page, not the dashboard', () => {
  assert.deepEqual(appMarkup('handover').rendered, ['HandoverPage']);
});

test('self-service page is available without granting additional asset permissions', () => {
  assert.deepEqual(appMarkup('selfService', { permissions: [] }).rendered, ['SelfServicePage']);
});

function handoverMarkup({ manager = false, selected = null, creating = false, currentUserId = 1 } = {}) {
  let index = 0;
  const react = { ...React, useState(initial) {
    const position = index++;
    return React.useState(position === 0 ? { items: [], totalElements: 0, totalPages: 0, page: 0, canManage: manager }
      : position === 5 ? false : position === 8 ? selected : position === 10 ? creating : initial);
  } };
  const { HandoverPage } = load('components/HandoverPage.tsx', {
    react, './Icons': load('components/Icons.tsx'), '../api': { api: {}, ApiError: class extends Error {} },
    './ConfirmDialog': { ConfirmDialog: () => null }, './HandoverPage.css': {},
  });
  return renderToStaticMarkup(React.createElement(HandoverPage, { currentUserId, assets: [], onNotify() {} }));
}
const taskFixture = () => ({
  id: 1, version: 1, kind: 'ONBOARDING', state: 'IN_PROGRESS', employeeUserId: 1, employeeName: '张三',
  department: '研发部', plannedDate: '2026-11-01', createdByName: '管理员', approvalReference: '', notes: '', events: [],
  items: [{ index: 0, assetId: 1, assetTag: 'TEST-001', name: '电脑', checked: true, checkedBy: '管理员', confirmedAt: null }],
});

test('ordinary employee sees self-service guidance without management buttons', () => {
  const markup = handoverMarkup();
  assert.match(markup, /只展示明确关联本账号/);
  assert.ok(!markup.includes('新建交接单'));
  assert.match(markup, /钉钉审批自动同步尚未启用/);
});

test('employee can acknowledge own checked item without manager controls', () => {
  const markup = handoverMarkup({ selected: taskFixture() });
  assert.match(markup, /我已收到/);
  assert.ok(!markup.includes('登记线下确认'));
  assert.ok(!markup.includes('完结交接单'));
  assert.ok(!handoverMarkup({ selected: taskFixture(), currentUserId: 2 }).includes('我已收到'));
});

test('manager sees explicit non-mutating workflow and offline evidence controls', () => {
  const markup = handoverMarkup({ selected: taskFixture(), manager: true });
  assert.match(markup, /登记线下确认/);
  assert.match(markup, /资产台账请在原资产页面核对处理/);
  assert.match(markup, /disabled="">完结交接单/);
});

test('new employee editor works with intranet-compatible UUID generation', () => {
  const markup = handoverMarkup({ manager: true, creating: true });
  assert.match(markup, /暂无账号／先手工登记/);
  assert.match(markup, /创建交接单（不修改台账）/);
  assert.match(markup, /手工交接项/);
});

test('handover API only writes dedicated endpoints with CSRF and expected version', async () => {
  const calls = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } : {} };
  } });
  await api.checkHandoverItem(12, 2, 7, true);
  await api.confirmHandoverItem(12, 2, 8, true, '纸质签收单001');
  await api.completeHandover(12, 9);
  assert.deepEqual(calls.map(call => call.url), ['/api/auth/csrf', '/api/handovers/12/items/2/check', '/api/handovers/12/items/2/confirm', '/api/handovers/12/complete']);
  assert.equal(JSON.parse(calls[1].options.body).version, 7);
  assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'test-csrf');
  assert.equal(JSON.parse(calls[2].options.body).offline, true);
});

function selfServiceMarkup({ user = {}, assets = [], loading = false, error = '', tab = 'assets' } = {}) {
  const react = { ...React, useState: () => React.useState(tab) };
  const { SelfServicePage } = load('components/SelfServicePage.tsx', {
    react, './Icons': load('components/Icons.tsx'), './SelfServicePage.css': {},
    './HandoverPage': { HandoverPage: props => React.createElement('div', {
      'data-personal-handovers': String(props.selfService), 'data-user': String(props.currentUserId),
    }, '本人交接') },
  });
  return renderToStaticMarkup(React.createElement(SelfServicePage, {
    user: { id: 1, username: 'zhangsan', displayName: '张三', permissions: ['ASSET_VIEW'], ...user },
    assets, loading, error, onRefresh() {}, onSelect() {}, onNotify() {},
  }));
}
const selfAsset = overrides => ({ id: 1, assetTag: 'SELF-001', name: '本人电脑', assignedUserId: 1,
  model: { name: '电脑型号' }, status: { name: '已经领出' }, category: { name: '笔记本' },
  location: { name: '公司' }, manufacturerSerialNumber: 'SN-SELF-001', ...overrides });

test('personal assets match account id rather than a same-name assignee or manager scope', () => {
  const markup = selfServiceMarkup({ user: { role: 'SUPER_ADMIN', dataScope: 'ALL' }, assets: [
    selfAsset(), selfAsset({ id: 2, assetTag: 'OTHER-002', name: '别人的电脑', assignedUserId: 2, assignedTo: '张三' }),
    selfAsset({ id: 3, assetTag: 'LEGACY-003', name: '未关联旧电脑', assignedUserId: null, assignedTo: '张三' }),
  ] });
  assert.match(markup, /SELF-001/);
  assert.match(markup, /SN-SELF-001/);
  assert.match(markup, /当前可见的本人资产：1 项/);
  assert.ok(!markup.includes('OTHER-002'));
  assert.ok(!markup.includes('LEGACY-003'));
  for (const action of ['删除资产', '编辑资产', '归还资产', '新增资产']) assert.ok(!markup.includes(action));
});

test('self-service does not render assets without permission or mistake a load error for no assets', () => {
  const restricted = selfServiceMarkup({ user: { permissions: [] }, assets: [selfAsset()] });
  assert.match(restricted, /未获查看资产权限/);
  assert.ok(!restricted.includes('SELF-001'));
  const failed = selfServiceMarkup({ error: '网络错误' });
  assert.match(failed, /资产读取失败/);
  assert.ok(!failed.includes('暂无明确关联本账号的资产'));
  assert.match(selfServiceMarkup({ loading: true }), /正在读取本人资产/);
  assert.match(selfServiceMarkup(), /没有记录不代表没有领用设备/);
});

test('personal handovers use the existing component in self-service mode even for administrators', () => {
  const markup = selfServiceMarkup({ tab: 'handovers', user: { role: 'SUPER_ADMIN', canManageUsers: true } });
  assert.match(markup, /data-personal-handovers="true"/);
  assert.match(markup, /data-user="1"/);
});

test('personal handover API reuses the list endpoint with a server-side mine filter', async () => {
  const calls = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 200, json: async () => ({}) };
  } });
  await api.listHandovers(1, 'OFFBOARDING', 'IN_PROGRESS', true);
  assert.equal(calls.length, 1);
  const url = new URL(calls[0].url, 'http://localhost');
  assert.equal(url.pathname, '/api/handovers');
  assert.equal(url.searchParams.get('mine'), 'true');
  assert.equal(url.searchParams.get('page'), '1');
  assert.equal(url.searchParams.get('state'), 'IN_PROGRESS');
  assert.equal(calls[0].options.credentials, 'include');
  assert.ok(!calls[0].options.method || calls[0].options.method === 'GET');
});

test('personal handover view requests only own records and rejects an unscoped backend response', async () => {
  for (const invalidScope of [false, true]) {
    const effects = [], values = [], calls = [];
    let stateIndex = 0;
    const react = { ...React,
      useState(initial) { const index = stateIndex++; values[index] = initial; return [initial, value => {
        values[index] = typeof value === 'function' ? value(values[index]) : value;
      }]; },
      useRef: initial => ({ current: initial }), useEffect: effect => effects.push(effect),
    };
    const { HandoverPage } = load('components/HandoverPage.tsx', {
      react, './Icons': load('components/Icons.tsx'), './HandoverPage.css': {},
      './ConfirmDialog': { ConfirmDialog: () => null },
      '../api': { ApiError: class extends Error {}, api: { async listHandovers(...args) {
        calls.push(args); return { items: [{ employeeUserId: invalidScope ? 2 : 1 }], page: 0,
          totalElements: 1, totalPages: 1, canManage: invalidScope };
      } } },
    });
    HandoverPage({ currentUserId: 1, assets: [], selfService: true, onNotify() {} });
    effects[0]();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(JSON.stringify(calls), JSON.stringify([[0, '', '', true]]));
    if (invalidScope) { assert.equal(values[0].items.length, 0); assert.match(values[6], /本人交接数据范围不一致/); }
    else { assert.equal(values[0].items[0].employeeUserId, 1); assert.equal(values[6], ''); }
    assert.equal(values[5], false);
  }
});
