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
  compiled = Object.fromEntries(await Promise.all(['App.tsx', 'api.ts', 'components/HandoverPage.tsx', 'components/SelfServicePage.tsx', 'components/Sidebar.tsx', 'components/Icons.tsx', 'components/OperationPage.tsx', 'components/InventoryPage.tsx', 'components/ConsumablePage.tsx', 'components/RegistrationPage.tsx', 'components/WorkflowNotifications.tsx', 'components/ManagementShared.tsx', 'components/WorkbenchPage.tsx', 'components/EquipmentKitPage.tsx', 'components/AssetSupportPage.tsx', 'components/ManagementReportPage.tsx', 'components/ProcurementPage.tsx', 'components/AssetHistoryPanel.tsx', 'components/AssetDetail.tsx'].map(async file => {
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
  const browser = { location: { pathname: '/' }, history: { state: null, pushState() {}, replaceState() {} }, localStorage: { getItem: () => String(collapsed) } };
  vm.runInNewContext(compiled[file], {
    module, exports: module.exports, window: browser, URLSearchParams, FormData, Blob, File, crypto: require('node:crypto').webcrypto, ...globals,
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
  assert.deepEqual(menuLabels(sidebarMarkup({ dingtalkLogin: true, permissions: [] })), ['驾驶舱', '我的待办', '员工自助', '资产', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '采购补货', '岗位配发模板', '保修与供应商', '管理统计', '用户', '日志', '数据']);
});

test('collapsed DingTalk menus retain their labels and accessible names', () => {
  const markup = sidebarMarkup({ dingtalkLogin: true }, true);
  assert.match(markup, /sidebar collapsed/);
  for (const label of ['驾驶舱', '员工自助', '资产', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '用户', '日志', '数据']) assert.ok(markup.includes(`aria-label="${label}"`));
  assert.ok(!markup.includes('aria-label="设置"'));
});

test('password login retains existing permission-based menus', () => {
  assert.deepEqual(menuLabels(sidebarMarkup()), ['驾驶舱', '我的待办', '员工自助', '资产', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '采购补货']);
  assert.deepEqual(menuLabels(sidebarMarkup({ permissions: [] })), ['驾驶舱', '我的待办', '员工自助', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '采购补货']);
  assert.deepEqual(menuLabels(sidebarMarkup({ permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '我的待办', '员工自助', '资产', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '采购补货', '数据']);
});

test('administrator keeps the settings menu with either login method', () => {
  for (const dingtalkLogin of [true, false]) {
    assert.deepEqual(menuLabels(sidebarMarkup({ canManageUsers: true, dingtalkLogin, permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '我的待办', '员工自助', '资产', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '采购补货', '岗位配发模板', '保修与供应商', '管理统计', '用户', '日志', '设置', '数据']);
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
  const click = children => React.Children.forEach(children, child => { if (!child) return; if (child.type === 'button') child.props.onClick(); else if (child.props?.children) click(child.props.children); });
  click(nav.props.children);
  assert.deepEqual(sections, ['dashboard', 'workbench', 'selfService', 'assets', 'handover', 'operations', 'inventory', 'repair', 'consumables', 'procurement', 'kits', 'support', 'reports', 'users', 'logs', 'transfer']);
  assert.deepEqual(filters, ['all']);
});

function appMarkup(section, overrides = {}) {
  const user = {
    id: 1, username: 'employee', displayName: '员工', role: 'USER',
    canManageUsers: false, dingtalkLogin: true, hasAvatar: false,
    permissions: ['ASSET_VIEW'], dataScope: 'SELF', departments: [], ...overrides,
  };
  let stateIndex = 0;
  const changes = [], componentProps = {};
  const react = { ...React, useState(initial) {
    const index = stateIndex++;
    const [value] = React.useState(index === 1 ? user : index === 2 ? false : index === 5 ? section : initial);
    return [value, next => changes.push({ index, value: typeof next === 'function' ? next(value) : next })];
  } };
  const icons = load('components/Icons.tsx');
  const sidebar = load('components/Sidebar.tsx', { './Icons': icons });
  const rendered = [];
  const componentNames = ['AssetDetail', 'AssetForm', 'AssetTable', 'LookupModal', 'LoginPage', 'UserManagement', 'SystemSettings', 'ConfirmDialog', 'AuditLogPage', 'AvatarEditor', 'AssetLabelPrintPreview', 'ComputerDashboard', 'AssetTransfer', 'HandoverPage', 'SelfServicePage', 'OperationPage', 'InventoryPage', 'ConsumablePage', 'WorkbenchPage', 'EquipmentKitPage', 'AssetSupportPage', 'ManagementReportPage', 'ProcurementPage', 'RegistrationPage'];
  const mocks = { react, './components/Icons': icons, './components/Sidebar': sidebar, './api': { api: {}, ApiError: class extends Error {} } };
  for (const name of componentNames) mocks[`./components/${name}`] = { [name]: props => {
    rendered.push(name);
    componentProps[name] = props;
    return React.createElement('div', null, name);
  } };
  mocks['./components/OperationPage'].canManageWorkflows = user => user.dataScope === 'ALL' && ['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CHECKOUT', 'ASSET_RETURN'].every(permission => user.permissions.includes(permission));
  const loaded = load('App.tsx', mocks);
  const App = loaded.default || loaded;
  return { markup: renderToStaticMarkup(React.createElement(App)), rendered, componentProps, changes };
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
    './ConfirmDialog': { ConfirmDialog: () => null }, './HandoverPage.css': {}, './EquipmentKitPage': { HandoverKitPicker: () => null },
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
    './RegistrationPage': { RegistrationPage: () => React.createElement('div', null, '设备登记组件') },
    './WorkflowNotifications': { WorkflowNotifications: () => React.createElement('div', null, '通知状态组件') },
    './OperationPage': { OperationPage: () => React.createElement('div', null, '工单组件') },
    './InventoryPage': { InventoryPage: () => React.createElement('div', null, '盘点组件') },
    './ConsumablePage': { ConsumablePage: () => React.createElement('div', null, '耗材组件') },
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
      './ConfirmDialog': { ConfirmDialog: () => null }, './EquipmentKitPage': { HandoverKitPicker: () => null },
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

for (const [section, component] of [['operations', 'OperationPage'], ['repair', 'OperationPage'], ['inventory', 'InventoryPage'], ['consumables', 'ConsumablePage']]) {
  test(`${section} opens its own workflow even without asset read permissions`, () => {
    assert.deepEqual(appMarkup(section, { permissions: [] }).rendered, [component]);
  });
}

const workflowUser = (manager = false, id = 1) => ({ id, username: 'employee', displayName: '员工', dataScope: manager ? 'ALL' : 'SELF', permissions: manager ? ['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CHECKOUT', 'ASSET_RETURN'] : [] });
function workflowMarkup(file, component, state = {}, props = {}) {
  let index = 0;
  const react = { ...React, useState(initial) { const position = index++; return React.useState(Object.hasOwn(state, position) ? state[position] : initial); } };
  const { [component]: Page } = load(`components/${file}.tsx`, {
    react, '../api': { api: {}, ApiError: class extends Error {} }, './WorkflowPages.css': {},
    './HandoverPage': { requestKey: () => '11111111-1111-4111-8111-111111111111' },
    './OperationPage': { canManageWorkflows: user => user.dataScope === 'ALL' && ['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CHECKOUT', 'ASSET_RETURN'].every(permission => user.permissions.includes(permission)) },
    './ConfirmDialog': { ConfirmDialog: () => React.createElement('div', null, '再次确认弹窗') },
  });
  return renderToStaticMarkup(React.createElement(Page, { user: workflowUser(), assets: [], onNotify() {}, onAssetsChanged() {}, ...props }));
}
const operationFixture = overrides => ({ task: { id: 9, version: 2, kind: 'ISSUE', state: 'WAITING_CONFIRM', ownerId: 1, ownerName: '申请人', targetId: 1, targetName: '本人', assetId: 1, assetTag: 'DEVICE-001', title: '办公设备', ...overrides }, assetVersion: 4, description: '办公需要', proposed: {}, outcome: null, repairCost: null, events: [] });
test('ordinary operation employee can confirm only own target and cannot complete or manage', () => {
  const own = workflowMarkup('OperationPage', 'OperationPage', { 7: operationFixture() });
  assert.match(own, /本人确认/);
  assert.match(own, /disabled="">本人确认/);
  assert.ok(!own.includes('受理并交员工确认'));
  assert.ok(!own.includes('完结并写入台账</button>'));
  const other = workflowMarkup('OperationPage', 'OperationPage', { 7: operationFixture({ targetId: 2, targetName: '接收人' }) });
  assert.ok(!other.includes('>本人确认</button>'));
  assert.match(other, /管理员不能代签/);
});
test('operation completion requires both explicit confirmation and note', () => {
  const data = { items: [], canManage: true };
  const detail = operationFixture({ state: 'CONFIRMED' });
  const locked = workflowMarkup('OperationPage', 'OperationPage', { 0: data, 7: detail }, { user: workflowUser(true) });
  assert.match(locked, /disabled="">完结并写入台账/);
  const ready = workflowMarkup('OperationPage', 'OperationPage', { 0: data, 7: detail, 11: '实物验收完成', 13: true }, { user: workflowUser(true) });
  assert.match(ready, /class="button primary">完结并写入台账/);
  assert.match(ready, /版本冲突时不会覆盖其他修改/);
});
test('issue editor does not select existing asset and repair picker contains only current account assets', () => {
  const issue = workflowMarkup('OperationPage', 'OperationPage', { 8: true });
  assert.ok(!issue.includes('请选择本人设备'));
  const repair = workflowMarkup('OperationPage', 'OperationPage', { 8: true }, { repair: true, assets: [selfAsset(), selfAsset({ id: 2, assignedUserId: 2, assetTag: 'FORBIDDEN-002' })] });
  assert.match(repair, /SELF-001/);
  assert.ok(!repair.includes('FORBIDDEN-002'));
  assert.match(repair, /提交申请（不修改台账）/);
});
const inventoryFixture = (manager = false) => ({ batch: { id: 3, version: 7, title: '部门盘点', department: '研发', dueDate: '2026-12-01', state: 'OPEN', total: 1, confirmed: 0, resolved: 0 }, items: [{ index: 0, assetId: 1, assetVersion: 4, assetTag: 'SELF-001', name: '电脑', ownerId: 1, ownerName: '本人', result: null, employeeNote: '', resolved: false, resolution: '', ledgerChanged: true }], events: [], canManage: manager });
test('employee inventory exposes own confirmation, discrepancy warning and no manager controls', () => {
  const markup = workflowMarkup('InventoryPage', 'InventoryPage', { 5: inventoryFixture() });
  assert.match(markup, /确认本人设备/);
  assert.match(markup, /台账在盘点发起后已变化/);
  assert.ok(!markup.includes('管理员核实／处理差异</button>'));
  assert.ok(!markup.includes('完成盘点</button>'));
});
test('inventory completion stays disabled until every item has manager resolution', () => {
  const markup = workflowMarkup('InventoryPage', 'InventoryPage', { 0: { items: [], canManage: true }, 5: inventoryFixture(true), 12: '实物已核对' }, { user: workflowUser(true) });
  assert.match(markup, /disabled="">完成盘点/);
  assert.match(markup, /管理员核实／处理差异/);
});
test('consumable employee sees stock warning but no inventory mutation controls', () => {
  const item = { id: 2, version: 1, name: '电池', unit: '节', quantity: 1, lowStockThreshold: 3, lowStock: true, notes: '库存测试' };
  const markup = workflowMarkup('ConsumablePage', 'ConsumablePage', { 11: item });
  assert.match(markup, /库存不足预警/);
  assert.ok(!markup.includes('保存入库'));
  assert.ok(!markup.includes('库存变动</h3>'));
  assert.ok(!markup.includes('修改名称／预警配置</button>'));
});
test('workflow API mutations retain CSRF, task and asset versions and never call direct asset mutation', async () => {
  const calls = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' } : {} };
  } });
  await api.listOperations(0, '', true);
  await api.operationAction(8, { version: 4, action: 'COMPLETE', assetVersion: 12, confirmed: true, note: '验收' });
  await api.inventoryAction(3, { version: 7, action: 'CONFIRM', index: 0, result: 'MISMATCH', note: '内存不符' });
  await api.moveConsumable(2, { requestKey: 'uuid', version: 3, kind: 'OUT', quantity: 2, recipientUsername: 'employee', note: '领用' });
  await api.updateConsumable(2, { requestKey: 'uuid-settings', version: 4, name: '电池', unit: '节', lowStockThreshold: 5, notes: '' });
  assert.equal(new URL(calls[0].url, 'http://localhost').searchParams.get('mine'), 'true');
  const writes = calls.filter(call => call.options.method === 'POST');
  assert.deepEqual(writes.map(call => call.url), ['/api/operations/8/actions', '/api/inventories/3/actions', '/api/consumables/2/movements', '/api/consumables/2/settings']);
  for (const call of writes) { assert.equal(call.options.headers['X-CSRF-TOKEN'], 'csrf'); assert.equal(call.options.credentials, 'include'); }
  assert.equal(JSON.parse(writes[0].options.body).assetVersion, 12);
  assert.equal(JSON.parse(writes[1].options.body).version, 7);
  assert.equal(JSON.parse(writes[3].options.body).requestKey, 'uuid-settings');
  assert.ok(!calls.some(call => call.url.startsWith('/api/assets')));
});

test('registration and notification tabs preserve independent review and administrator permissions', () => {
  const ordinary = selfServiceMarkup({ user: { permissions: [] }, tab: 'registrations' });
  assert.match(ordinary, /设备登记组件/);
  assert.ok(!ordinary.includes('登记审核</button>'));
  assert.ok(!ordinary.includes('通知状态</button>'));
  assert.ok(!selfServiceMarkup({ tab: 'review', user: { permissions: [] } }).includes('设备登记组件'));
  assert.ok(!selfServiceMarkup({ tab: 'notifications' }).includes('通知状态组件'));
  assert.match(selfServiceMarkup({ tab: 'notifications', user: { canManageUsers: true } }), /通知状态组件/);
  assert.match(selfServiceMarkup({ tab: 'review', user: { dataScope: 'ALL', permissions: ['ASSET_VIEW', 'ASSET_CREATE', 'ASSET_CHECKOUT'] } }), /设备登记组件/);
});

function registrationMarkup(state = {}, props = {}) {
  let index = 0;
  const react = { ...React, useState(initial) { const position = index++; return React.useState(Object.hasOwn(state, position) ? state[position] : initial); } };
  const { RegistrationPage } = load('components/RegistrationPage.tsx', {
    react, '../api': { api: {}, ApiError: class extends Error {} }, './RegistrationPage.css': {},
    './AssetForm': { prepareImage: async () => '' }, './Icons': load('components/Icons.tsx'),
    './HandoverPage': { requestKey: () => '11111111-1111-4111-8111-111111111111' },
    './ConfirmDialog': { ConfirmDialog: () => React.createElement('div', null, '确认审核弹窗') },
  });
  return renderToStaticMarkup(React.createElement(RegistrationPage, { user: workflowUser(), assets: [], lookups: [], onNotify() {}, onAssetsChanged() {}, ...props }));
}
const registrationFixture = () => ({ request: { id: 5, version: 4, ownerId: 1, ownerName: '本人', name: '登记笔记本', model: '型号', serialNumber: 'SN-TEST', state: 'SUBMITTED' }, requestKey: 'uuid', department: '研发', location: '公司', cpu: 'I7', memory: '32G', storage: '1T', notes: '待核实', photos: [], reviewNote: '', events: [], serialAlreadyInAssets: false });
test('registration review needs explicit physical confirmation and does not expose review actions to employees', () => {
  const detail = registrationFixture();
  const ordinary = registrationMarkup({ 6: detail });
  assert.match(ordinary, /撤回本次登记/);
  assert.ok(!ordinary.includes('审核通过并新增资产</button>'));
  const manager = registrationMarkup({ 0: { items: [], canReview: true }, 6: detail }, { review: true });
  assert.match(manager, /disabled="">审核通过并新增资产/);
  assert.match(manager, /disabled="">审核通过并记录关联/);
  assert.match(manager, /我已核对实物/);
});
test('registration multipart and review APIs keep CSRF and explicit versions without direct asset writes', async () => {
  const calls = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' } : {} };
  } });
  await api.submitRegistration({ requestKey: 'uuid', name: '电脑', model: '型号', serialNumber: 'sn' }, [new File(['photo'], 'photo.png', { type: 'image/png' })]);
  await api.approveExistingRegistration(5, 4, 12, 7, '已核对', true);
  await api.retryTaskNotification(9);
  const writes = calls.filter(call => call.options.method === 'POST');
  assert.deepEqual(writes.map(call => call.url), ['/api/registrations', '/api/registrations/5/approve-existing', '/api/task-notifications/9/retry']);
  assert.equal(writes[0].options.headers['Content-Type'], undefined);
  assert.equal(writes[0].options.body.get('photos').name, 'photo.png');
  assert.equal(JSON.parse(await writes[0].options.body.get('data').text()).requestKey, 'uuid');
  assert.equal(JSON.parse(writes[1].options.body).assetVersion, 7);
  for (const call of writes) assert.equal(call.options.headers['X-CSRF-TOKEN'], 'csrf');
  assert.ok(!calls.some(call => call.url.startsWith('/api/assets')));
});

for (const [section, component] of [['workbench', 'WorkbenchPage'], ['kits', 'EquipmentKitPage'], ['support', 'AssetSupportPage'], ['reports', 'ManagementReportPage'], ['procurement', 'ProcurementPage'], ['registrations', 'RegistrationPage']]) {
  test(`${section} mounts its dedicated management component`, () => {
    assert.deepEqual(appMarkup(section).rendered, [component]);
  });
}

test('workbench navigation retains record identity and uses personal mode for supplemental registration', () => {
  const reviewer = { dataScope: 'ALL', permissions: ['ASSET_VIEW', 'ASSET_CREATE', 'ASSET_CHECKOUT'] };
  for (const [state, expectedReview] of [['SUBMITTED', true], ['NEEDS_INFO', false]]) {
    const view = appMarkup('workbench', reviewer);
    view.componentProps.WorkbenchPage.onOpen('registrations', 8, state);
    assert.equal(view.changes.find(c => c.index === 5).value, 'registrations');
    assert.deepEqual(JSON.parse(JSON.stringify(view.changes.find(c => c.index === 6).value)), { name: 'list', taskId: 8, review: expectedReview });
  }
  const ordinary = appMarkup('workbench');
  ordinary.componentProps.WorkbenchPage.onOpen('registrations', 8, 'SUBMITTED');
  assert.equal(ordinary.changes.find(c => c.index === 6).value.review, false);
});

function managementMarkup(file, component, { data = null, state = {}, user = workflowUser(), props = {}, error = '' } = {}) {
  let index = 0;
  const enabledCalls = [];
  const react = { ...React, useState(initial) { const position = index++; return React.useState(Object.hasOwn(state, position) ? state[position] : initial); } };
  const shared = load('components/ManagementShared.tsx', { './WorkflowPages.css': {} });
  const { [component]: Page } = load(`components/${file}.tsx`, {
    react, '../api': { api: {}, ApiError: class extends Error {} },
    './ManagementShared': { ...shared, useRemote: (_loader, _deps, enabled = true) => { enabledCalls.push(enabled); return { data, loading: false, error, refresh() {} }; } },
    './OperationPage': { canManageWorkflows: u => u.dataScope === 'ALL' && ['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CHECKOUT', 'ASSET_RETURN'].every(permission => u.permissions.includes(permission)) },
    './HandoverPage': { requestKey: () => '11111111-1111-4111-8111-111111111111' },
    './AssetSupportPage': { SupplierSelect: () => null },
    './ConfirmDialog': { ConfirmDialog: () => React.createElement('div', null, '再次确认弹窗') },
  });
  return { markup: renderToStaticMarkup(React.createElement(Page, { user, assets: [], onNotify() {}, ...props })), enabledCalls };
}

test('sensitive management pages deny ordinary employees and disable all data loaders', () => {
  for (const name of ['EquipmentKitPage', 'AssetSupportPage', 'ManagementReportPage']) {
    const view = managementMarkup(name, name);
    assert.match(view.markup, /未获得此功能的管理权限/);
    assert.ok(view.enabledCalls.length > 0);
    assert.ok(view.enabledCalls.every(enabled => !enabled));
  }
});

test('disabled or failed remote loads never retain unauthorized or stale records', async () => {
  for (const enabled of [false, true]) {
    const values = [], effects = []; let position = 0, calls = 0;
    const react = { useState(initial) { const index = position++; values[index] = initial; return [initial, value => { values[index] = value; }]; }, useRef: initial => ({ current: initial }), useEffect: callback => effects.push(callback) };
    const { useRemote } = load('components/ManagementShared.tsx', { react, './WorkflowPages.css': {} }, false, { Error });
    useRemote(async () => { calls++; throw new Error('已失去权限'); }, [], enabled);
    effects[0](); await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls, enabled ? 1 : 0); assert.equal(values[0], null); assert.equal(values[1], false);
    assert.equal(values[2], enabled ? '已失去权限' : '');
  }
});

test('active equipment templates are selectable but do not allocate real assets or stock', () => {
  const kit = { id: 1, version: 2, name: '开发岗位', active: true, notes: '', items: [{ name: '电脑', quantity: 1 }], manualItems: ['电脑'] };
  const data = { items: [kit, { ...kit, id: 2, name: '已停用的模板', active: false }], totalPages: 1, totalElements: 2, page: 0 };
  const { markup } = managementMarkup('EquipmentKitPage', 'HandoverKitPicker', { data, state: { 1: 1 }, props: { disabled: false, onApply() {} } });
  assert.match(markup, /开发岗位/); assert.ok(!markup.includes('已停用的模板')); assert.match(markup, /只追加手工准备项，不自动选择实物、不扣库存/);
  assert.match(markup, /class="button ghost">追加到准备清单/);
});

const procurementFixture = overrides => ({ order: { id: 7, version: 3, ownerId: 1, ownerName: '员工', kind: 'ASSET', state: 'ORDERED', title: '电脑采购', quantity: 1, unit: '台', estimatedUnitPrice: 5000, neededDate: null, createdAt: '2026-10-18T09:00:00', ...overrides }, reason: '办公使用', orderNumber: 'ORDER-1', supplierName: '供应商', expectedDate: '2026-11-01', orderedUnitPrice: 4900, assetIds: [], events: [] });
test('procurement receipt stays disabled without explicit confirmation and excludes historical equipment', () => {
  const data = { items: [], page: 0, totalPages: 0, totalElements: 0, canManage: true };
  const assets = [selfAsset({ id: 1, assetTag: 'NEW-RECEIPT', assignedUserId: null, assignedTo: '', checkedOut: false, status: { name: '当前可用' }, createdAt: '2026-10-18T10:00:00' }), selfAsset({ id: 2, assetTag: 'OLD-LEDGER', assignedUserId: null, checkedOut: false, status: { name: '当前可用' }, createdAt: '2026-10-17T10:00:00' })];
  const options = { data, user: workflowUser(true), state: { 3: procurementFixture(), 6: '实物核对完成', 13: [1] }, props: { assets } };
  const locked = managementMarkup('ProcurementPage', 'ProcurementPage', options).markup;
  assert.match(locked, /NEW-RECEIPT/); assert.ok(!locked.includes('OLD-LEDGER')); assert.match(locked, /disabled="">整单验收并记录入库/);
  const ready = managementMarkup('ProcurementPage', 'ProcurementPage', { ...options, state: { ...options.state, 14: true, 15: 'RECEIVE' } }).markup;
  assert.match(ready, /class="button primary">整单验收并记录入库/); assert.match(ready, /再次确认弹窗/);
  assert.match(ready, /不代替公司财务审批、付款或供应商下单/);
});
test('ordinary procurement users have only their own cancellation, not approval or receipt controls', () => {
  const view = managementMarkup('ProcurementPage', 'ProcurementPage', { data: { items: [], canManage: false }, state: { 3: procurementFixture({ state: 'SUBMITTED' }), 6: '不再需要' } }).markup;
  assert.match(view, /取消并保留历史/); assert.ok(!view.includes('内部需求审核通过</button>')); assert.ok(!view.includes('整单验收并记录入库</button>'));
});

test('asset history is loaded only after the detail history section is opened', () => {
  for (const opened of [false, true]) {
    let index = 0, historyMounts = 0;
    const react = { ...React, useState(initial) { return React.useState(index++ === 2 ? opened : initial); } };
    const { AssetDetail } = load('components/AssetDetail.tsx', { react, './Icons': load('components/Icons.tsx'), './AssetImageLightbox': { AssetImageLightbox: () => null }, './AssetHistoryPanel': { AssetHistoryPanel: () => { historyMounts++; return null; } } });
    renderToStaticMarkup(React.createElement(AssetDetail, { asset: selfAsset({ company: { name: '公司' }, createdAt: '2026-10-18T10:00:00', updatedAt: '2026-10-18T10:00:00' }), onClose() {} }));
    assert.equal(historyMounts, opened ? 1 : 0);
  }
});

test('management and procurement APIs retain CSRF, versions and independent endpoints', async () => {
  const calls = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' } : {} };
  } });
  await api.workbench(); await api.assetHistory(9); await api.managementReports(); await api.listWarranties(1, 'due'); await api.listProcurements(2, true);
  await api.saveKit(3, { requestKey: 'uuid', version: 2, name: '岗位', active: true, items: [] });
  await api.saveSupplier(null, { requestKey: 'uuid-supplier', name: '供应商', active: true });
  await api.saveWarranty(9, { version: 4, warrantyEnd: '2027-01-01' });
  await api.procurementAction(7, { version: 3, action: 'RECEIVE', note: '核对', confirmed: true, consumableId: 2, consumableVersion: 5 });
  assert.equal(calls[0].url, '/api/management/workbench'); assert.equal(calls[1].url, '/api/management/history/9');
  assert.equal(new URL(calls[4].url, 'http://localhost').searchParams.get('mine'), 'true');
  const writes = calls.filter(call => ['POST', 'PUT'].includes(call.options.method));
  assert.deepEqual(writes.map(call => call.url), ['/api/management/kits/3', '/api/management/suppliers', '/api/management/warranties/9', '/api/procurements/7/actions']);
  for (const call of writes) { assert.equal(call.options.headers['X-CSRF-TOKEN'], 'csrf'); assert.equal(call.options.credentials, 'include'); }
  assert.equal(JSON.parse(writes[2].options.body).version, 4); assert.equal(JSON.parse(writes[3].options.body).consumableVersion, 5);
  assert.ok(!calls.some(call => call.url.startsWith('/api/assets')));
});
