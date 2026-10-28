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
  const bundleFiles = ['assetBundle.ts', 'components/NewDisplayEditor.tsx', 'components/AssetLabelPrintPreview.tsx'];
  compiled = Object.fromEntries(await Promise.all(['App.tsx', 'api.ts', 'components/HandoverPage.tsx', 'components/SelfServicePage.tsx', 'components/Sidebar.tsx', 'components/Icons.tsx', 'components/OperationPage.tsx', 'components/InventoryPage.tsx', 'components/ConsumablePage.tsx', 'components/RegistrationPage.tsx', 'components/WorkflowNotifications.tsx', 'components/ManagementShared.tsx', 'components/WorkbenchPage.tsx', 'components/EquipmentKitPage.tsx', 'components/AssetSupportPage.tsx', 'components/ManagementReportPage.tsx', 'components/ProcurementPage.tsx', 'components/AssetHistoryPanel.tsx', 'components/AssetDetail.tsx', 'components/LoginPage.tsx', 'components/DingTalkQrLogin.tsx', 'components/DingTalkOaPage.tsx', 'domainJoin.ts', 'components/DomainJoinBadge.tsx', 'components/AssetTable.tsx', 'components/AssetForm.tsx', 'components/FoundationData.tsx', 'components/ModelOptions.tsx', 'components/EditableCombobox.tsx', 'components/AccessoryOptions.tsx', 'components/AssetAccessories.tsx'].map(async file => {
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
  for (const file of bundleFiles) {
    const result = await build({ root: frontend, configFile: false, logLevel: 'silent', build: { ssr: true, write: false, minify: false,
      rollupOptions: { input: path.join(frontend, 'src', file), external: (_id, importer) => Boolean(importer), output: { format: 'cjs' } } } });
    compiled[file] = result.output.find(item => item.type === 'chunk').code;
  }
});

function load(file, mocks = {}, collapsed = false, globals = {}) {
  const module = { exports: {} };
  const browser = { location: { pathname: '/' }, history: { state: null, pushState() {}, replaceState() {} }, localStorage: { getItem: () => String(collapsed) } };
  vm.runInNewContext(compiled[file], {
    module, exports: module.exports, window: browser, URLSearchParams, FormData, Blob, File, TextEncoder, crypto: require('node:crypto').webcrypto, ...globals,
    require: id => {
      if (Object.hasOwn(mocks, id)) return mocks[id];
      if (id.endsWith('.css')) return {};
      const local = path.posix.normalize(path.posix.join(path.posix.dirname(file), id));
      const target = id.startsWith('.') && [local, `${local}.ts`, `${local}.tsx`].find(candidate => Object.hasOwn(compiled, candidate));
      return target ? load(target, {}, collapsed, globals) : frontendRequire(id);
    },
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
    assert.deepEqual(menuLabels(sidebarMarkup({ canManageUsers: true, dingtalkLogin, permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '我的待办', '员工自助', '资产', '入离职交接', '领用归还', '资产盘点', '报修维修', '耗材管理', '采购补货', '岗位配发模板', '保修与供应商', '管理统计', '用户', '日志', '基础资料', '设置', '数据']);
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

function appMarkup(section, overrides = {}, initialPage = { name: 'list' }, options = {}) {
  const user = {
    id: 1, username: 'employee', displayName: '员工', role: 'USER',
    canManageUsers: false, dingtalkLogin: true, hasAvatar: false,
    permissions: ['ASSET_VIEW'], dataScope: 'SELF', departments: [], ...overrides,
  };
  let stateIndex = 0;
  const changes = [], componentProps = {};
  const react = { ...React, useState(initial) {
    const index = stateIndex++;
    const [value] = React.useState(index === 1 ? user : index === 2 ? false : index === 5 ? section : index === 6 ? initialPage : index === 7 && options.assets ? options.assets : index === 16 && options.search !== undefined ? options.search : index === 17 && options.loading !== undefined ? options.loading : index === 33 && options.domainFilter !== undefined ? options.domainFilter : initial);
    return [value, next => changes.push({ index, value: typeof next === 'function' ? next(value) : next })];
  } };
  const icons = load('components/Icons.tsx');
  const sidebar = load('components/Sidebar.tsx', { './Icons': icons });
  const rendered = [];
  const componentNames = ['AssetDetail', 'AssetForm', 'AssetTable', 'LookupModal', 'LoginPage', 'UserManagement', 'SystemSettings', 'FoundationData', 'ConfirmDialog', 'AuditLogPage', 'AvatarEditor', 'AssetLabelPrintPreview', 'ComputerDashboard', 'AssetTransfer', 'HandoverPage', 'SelfServicePage', 'OperationPage', 'InventoryPage', 'ConsumablePage', 'WorkbenchPage', 'EquipmentKitPage', 'AssetSupportPage', 'ManagementReportPage', 'ProcurementPage', 'RegistrationPage', 'DingTalkOaPage'];
  const mocks = { react, './components/Icons': icons, './components/Sidebar': sidebar, './api': { api: options.api || {}, ApiError: class extends Error {} } };
  for (const name of componentNames) mocks[`./components/${name}`] = { [name]: props => {
    rendered.push(name);
    componentProps[name] = props;
    return React.createElement('div', null, name);
  } };
  mocks['./components/OperationPage'].canManageWorkflows = user => user.dataScope === 'ALL' && ['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CHECKOUT', 'ASSET_RETURN'].every(permission => user.permissions.includes(permission));
  const loaded = load('App.tsx', mocks);
  const App = loaded.default || loaded;
  let tree;
  const Capture = () => { tree = App(); return tree; };
  return { markup: renderToStaticMarkup(React.createElement(Capture)), rendered, componentProps, changes, tree };
}

function findNativeElement(tree, predicate) {
  let found;
  const visit = value => React.Children.forEach(value, child => {
    if (found || !React.isValidElement(child)) return;
    if (predicate(child)) found = child;
    else visit(child.props.children);
  });
  visit(tree);
  return found;
}

test('asset search exposes a clickable submit button and shares its handler with Enter', async () => {
  const terms = [];
  const result = appMarkup('assets', {}, { name: 'list' }, { search: 'HUAWEI', loading: false, api: {
    listAssets: async term => { terms.push(term); return []; }, listLookups: async () => [],
    summary: async () => ({ total: 0, available: 0, checkedOut: 0, maintenance: 0, scrapped: 0 }), dashboard: async () => null,
  } });
  const form = findNativeElement(result.tree, child => child.type === 'form' && child.props.role === 'search');
  assert.ok(form);
  const button = findNativeElement(form, child => child.type === 'button');
  assert.equal(button.props.type, 'submit');
  assert.equal(findNativeElement(button, child => child.props.className === 'asset-search-submit-label').props.children, '搜索');
  assert.ok(button.props.className.includes('asset-search-submit'));
  assert.equal(findNativeElement(button, child => child.props.className === 'asset-search-submit-icon').props['aria-hidden'], 'true');
  assert.equal(button.props.disabled, false);
  assert.equal(button.props.onClick, undefined, 'native form submission must not issue a second click request');
  let prevented = 0;
  form.props.onSubmit({ preventDefault() { prevented++; } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(prevented, 1);
  assert.deepEqual(terms, ['HUAWEI', '']); // Existing catalogue refresh is preserved.
});

test('asset search cannot submit again while its data is loading', () => {
  const result = appMarkup('assets', {}, { name: 'list' }, { loading: true });
  const form = findNativeElement(result.tree, child => child.type === 'form' && child.props.role === 'search');
  const button = findNativeElement(form, child => child.type === 'button');
  assert.equal(button.props.disabled, true);
  assert.equal(findNativeElement(button, child => child.props.className === 'asset-search-submit-label').props.children, '搜索中…');
  assert.equal(button.props['aria-busy'], true);
  const previous = result.changes.length;
  form.props.onSubmit({ preventDefault() {} });
  assert.equal(result.changes.length, previous);
});

test('OA entry points share one page and retain the existing handover route', () => {
  for (const [section, name] of [['handover', 'HandoverPage'], ['operations', 'OperationPage'], ['selfService', 'SelfServicePage']]) {
    const result = appMarkup(section);
    result.componentProps[name].onOpenOa();
    assert.ok(result.changes.some(change => change.index === 5 && change.value === 'handover'));
    assert.ok(result.changes.some(change => change.index === 6 && change.value.oa === true));
  }
  const result = appMarkup('handover', {}, { name: 'list', oa: true });
  assert.deepEqual(result.rendered, ['DingTalkOaPage']);
  result.componentProps.DingTalkOaPage.onBack();
  assert.ok(result.changes.some(change => change.index === 6 && !change.value.oa));
});

function oaMarkup(settings, items = [], manager = false, creating = false) {
  let index = 0;
  const { DingTalkOaPage } = load('components/DingTalkOaPage.tsx', {
    react: { ...React, useState(initial) {
      const i = index++;
      return React.useState(i === 0 ? settings : i === 1 ? { items, page: 0, totalElements: items.length, totalPages: 1, canManage: manager } : i === 5 ? false : i === 7 ? creating : initial);
    } }, '../api': { api: {} }, './HandoverPage': { requestKey: () => 'test-key' },
    './OperationPage': { canManageWorkflows: () => manager }, './ConfirmDialog': { ConfirmDialog: () => null }, './WorkflowPages.css': {},
  });
  return renderToStaticMarkup(React.createElement(DingTalkOaPage, { user: { id: 1 }, onBack() {}, onNotify() {}, onAssetsChanged() {} }));
}

test('OA disabled and incomplete identity are explicit and cannot submit', () => {
  const disabled = oaMarkup({ enabled: false, identityReady: true, streamEnabled: false });
  assert.match(disabled, /钉钉 OA 尚未启用/);
  assert.match(disabled, /disabled="">发起实物交接审批/);
  const missing = oaMarkup({ enabled: true, identityReady: false, streamEnabled: true });
  assert.match(missing, /请先用钉钉重新登录或绑定账号/);
  assert.match(missing, /disabled="">发起实物交接审批/);
});

test('OA form requires physical employee attestation and distinguishes failure from ledger completion', () => {
  const markup = oaMarkup({ enabled: true, identityReady: true, streamEnabled: true }, [
    { id: 1, ownerId: 1, kind: 'ISSUE', approvalState: 'LEDGER_FAILED', error: '资产已变化', instanceId: 'instance-1', assetTag: 'PC-1' },
    { id: 2, ownerId: 1, kind: 'RETURN', approvalState: 'APPLIED', instanceId: 'instance-2', assetTag: 'PC-2' },
  ], false, true);
  assert.match(markup, /本人已核对设备编号、实物和配件/);
  assert.match(markup, /未入账，请处理/);
  assert.match(markup, /审批通过，已入账/);
  assert.ok(!markup.includes('关闭异常（不改台账）'));
  assert.ok(!markup.includes('核实发起结果'));
});

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

function handoverMarkup({ manager = false, selected = null, creating = false, currentUserId = 1, ...props } = {}) {
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
  return renderToStaticMarkup(React.createElement(HandoverPage, { currentUserId, assets: [], onNotify() {}, ...props }));
}
const taskFixture = () => ({
  id: 1, version: 1, kind: 'ONBOARDING', state: 'IN_PROGRESS', employeeUserId: 1, employeeName: '张三',
  department: '研发部', plannedDate: '2026-11-01', createdByName: '管理员', approvalReference: '', notes: '', events: [],
  items: [{ index: 0, assetId: 1, assetTag: 'TEST-001', name: '电脑', checked: true, checkedBy: '管理员', confirmedAt: null }],
});

test('ordinary employee sees handover results without management buttons or permanent explanatory banners', () => {
  const markup = handoverMarkup();
  assert.match(markup, /入离职交接/);
  assert.ok(!markup.includes('新建交接单'));
  assert.ok(!markup.includes('handover-notice'));
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
  assert.match(markup, /旧版记录 · 不自动更新台账/);
  assert.match(markup, /disabled="">完结交接单/);
});

test('new employee editor works with intranet-compatible UUID generation', () => {
  const markup = handoverMarkup({ manager: true, creating: true });
  assert.match(markup, /暂无账号／先手工登记/);
  assert.match(markup, /创建交接单/);
  assert.match(markup, /建单后预留/);
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
  assert.equal(JSON.parse(calls[3].options.body).confirmed, false);
  await api.completeHandover(12, 10, true);
  await api.bindHandoverAsset(12, 2, 11, 42, 6);
  assert.equal(JSON.parse(calls[4].options.body).confirmed, true);
  assert.equal(calls[5].url, '/api/handovers/12/items/2/asset');
  assert.deepEqual(JSON.parse(calls[5].options.body), { version: 11, assetId: 42, assetVersion: 6 });
  assert.equal(calls[5].options.headers['X-CSRF-TOKEN'], 'test-csrf');
});

test('integrated handover exposes a single final ledger action and linked operation without self-verification', () => {
  const selected = { ...taskFixture(), ledgerEnabled: true };
  selected.items[0].operationId = 27;
  const markup = handoverMarkup({ manager: true, selected, currentUserId: 2, onOpenOperation() {} });
  assert.match(markup, /disabled="">完结并更新台账/);
  assert.match(markup, /查看领用工单 #27/);
  assert.ok(!markup.includes('旧版记录'));
  const self = handoverMarkup({ manager: true, selected });
  assert.match(self, /我已收到/);
  assert.ok(!self.includes('撤销验收'));
  assert.ok(!self.includes('登记线下确认'));
});

test('unbound integrated preparation requires account linking and offers real-device association', () => {
  const selected = { ...taskFixture(), ledgerEnabled: true, employeeUserId: null };
  selected.items[0] = { ...selected.items[0], assetId: null, assetTag: null, checked: false };
  const markup = handoverMarkup({ manager: true, selected });
  assert.match(markup, /请先关联员工账号/);
  assert.match(markup, /关联实际设备/);
  assert.ok(!markup.includes('确认已配发'));
});

function handoverHarness(api, selected = null, overrides = {}) {
  const states = { 0: { items: [], canManage: true, page: 0, totalElements: 0, totalPages: 0 }, 5: false, 8: selected };
  const refs = [], notices = []; let index = 0, refIndex = 0, refreshed = 0;
  const react = { useState(initial) { const key = index++; if (!(key in states)) states[key] = typeof initial === 'function' ? initial() : initial; return [states[key], value => { states[key] = typeof value === 'function' ? value(states[key]) : value; }]; }, useRef(initial) { const key = refIndex++; return refs[key] ??= { current: initial }; }, useEffect() {} };
  const { HandoverPage } = load('components/HandoverPage.tsx', { react, '../api': { api, ApiError: class extends Error {} }, './HandoverPage.css': {}, './ConfirmDialog': { ConfirmDialog: () => null }, './EquipmentKitPage': { HandoverKitPicker: () => null } });
  return { states, notices, get refreshed() { return refreshed; }, render() { index = 0; refIndex = 0; return treeElements(HandoverPage({ currentUserId: 2, assets: [], onAssetsChanged() { refreshed++; }, onNotify: text => notices.push(text), ...overrides })); }, editor(element) { index = 100; refIndex = 100; return treeElements(element.type(element.props)); } };
}

test('final integrated confirmation sends reviewed version and refreshes assets only on successful ledger commit', async () => {
  const selected = { ...taskFixture(), ledgerEnabled: true };
  selected.items[0].confirmedAt = '2026-11-01T12:00:00';
  const calls = []; let release;
  const view = handoverHarness({ completeHandover: (...args) => { calls.push(args); return new Promise(resolve => { release = resolve; }); } }, selected);
  view.render().find(item => item.type === 'button' && item.props.children === '完结并更新台账').props.onClick();
  const dialog = view.render().find(item => item.props.title === '完结并更新台账');
  const first = dialog.props.onConfirm(); dialog.props.onConfirm();
  assert.deepEqual(calls, [[1, 1, true]]); assert.equal(view.refreshed, 0);
  release({ ...selected, state: 'COMPLETED', ledgerAppliedAt: '2026-11-01T12:01:00' }); await first; await new Promise(setImmediate);
  assert.equal(view.refreshed, 1); assert.match(view.notices[0], /台账和领用归还记录已更新/);
});

test('legacy completion never opts into ledger writes or refreshes assets', async () => {
  const selected = taskFixture(); selected.items[0].confirmedAt = '2026-11-01T12:00:00';
  const calls = [], view = handoverHarness({ completeHandover: async (...args) => { calls.push(args); return { ...selected, state: 'COMPLETED' }; } }, selected);
  view.render().find(item => item.type === 'button' && item.props.children === '完结交接单').props.onClick();
  await view.render().find(item => item.props.title === '完结交接单').props.onConfirm();
  assert.deepEqual(calls, [[1, 1, false]]); assert.equal(view.refreshed, 0);
});

test('new handover sends exact versions for selected and auto-listed devices without direct asset writes', async () => {
  let payload;
  const devices = [{ ...selfAsset(), id: 1, version: 4 }, { ...selfAsset(), id: 2, version: 7 }];
  const view = handoverHarness({ createHandover: async value => { payload = value; return taskFixture(); } }, null, { assets: devices });
  view.states[10] = true;
  const editor = view.render().find(item => typeof item.type === 'function' && item.props.onCreated);
  view.editor(editor);
  view.states[100] = { requestKey: 'test-key', kind: 'OFFBOARDING', employeeUserId: 1, employeeName: '张三', department: '', plannedDate: null, approvalReference: '', notes: '', assetIds: [1], includeAssignedAssets: true, allowLegacyAssets: false, manualItems: [] };
  view.states[101] = '核对适配器';
  await view.editor(editor).find(item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(payload.ledgerEnabled, true);
  assert.deepEqual(JSON.parse(JSON.stringify(payload.assetVersions)), { 1: 4, 2: 7 });
  assert.deepEqual(Array.from(payload.manualItems), ['核对适配器']);
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
test('handover-linked operations link back to their parent and never expose a second approval path', () => {
  for (const state of ['HANDOVER_PENDING', 'WAITING_CONFIRM', 'CONFIRMED', 'COMPLETED']) {
    const detail = operationFixture({ state, handoverId: 18 });
    const markup = workflowMarkup('OperationPage', 'OperationPage', { 0: { items: [], canManage: true }, 7: detail, 11: '验收完成', 13: true }, { user: workflowUser(true), onOpenHandover() {} });
    assert.match(markup, /查看交接单 #18/);
    for (const label of ['受理并交员工确认', '>本人确认</button>', '>完结并写入台账</button>', '>取消并保留记录</button>']) assert.ok(!markup.includes(label));
  }
});
test('handover and operation navigation preserve record IDs in both directions including self-service', () => {
  const handover = appMarkup('handover'); handover.componentProps.HandoverPage.onOpenOperation(29);
  assert.ok(handover.changes.some(change => change.index === 5 && change.value === 'operations'));
  assert.ok(handover.changes.some(change => change.index === 6 && change.value.taskId === 29));
  const operation = appMarkup('operations'); operation.componentProps.OperationPage.onOpenHandover(18);
  assert.ok(operation.changes.some(change => change.index === 5 && change.value === 'handover'));
  assert.ok(operation.changes.some(change => change.index === 6 && change.value.taskId === 18));
  const personal = appMarkup('selfService'); personal.componentProps.SelfServicePage.onOpenHandover(18);
  assert.ok(personal.changes.some(change => change.index === 6 && change.value.taskId === 18));
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

function assetDetailMarkup(overrides = {}) {
  const { AssetDetail } = load('components/AssetDetail.tsx', {
    './Icons': load('components/Icons.tsx'), './AssetImageLightbox': { AssetImageLightbox: () => null },
    './AssetHistoryPanel': { AssetHistoryPanel: () => null },
  });
  return renderToStaticMarkup(React.createElement(AssetDetail, { asset: selfAsset({
    company: { name: '公司' }, checkedOut: true, assignedTo: '袁玉杰',
    createdAt: '2026-10-23T10:00:00', updatedAt: '2026-10-23T10:00:00', ...overrides,
  }), onClose() {} }));
}

test('asset detail highlights the actual assignee separately from custody and lifecycle status', () => {
  const markup = assetDetailMarkup();
  assert.match(markup, /record-custody-row/);
  assert.match(markup, /class="record-assignee-chip" aria-label="领用人：袁玉杰"/);
  assert.match(markup, /class="record-assignee-name">袁玉杰<\/span>/);
  assert.match(markup, /class="record-custody-state">已经领出<\/span>/);
  for (const name of ['维护中', '已报废']) {
    const lifecycle = assetDetailMarkup({ status: { name } });
    assert.match(lifecycle, new RegExp(`class="record-custody-state">${name}<`));
    assert.match(lifecycle, /领用人：袁玉杰/);
  }
});

test('missing assignee is a warning rather than a fabricated employee and idle assets have no assignee badge', () => {
  for (const assignedTo of [null, '', '   ']) {
    const markup = assetDetailMarkup({ assignedTo });
    assert.match(markup, /class="record-assignee-chip missing" aria-label="领用人未填写"/);
    assert.match(markup, /未填写领用人/);
  }
  const available = assetDetailMarkup({ checkedOut: false, assignedTo: '历史文字', status: { name: '当前可用' } });
  assert.ok(!available.includes('record-assignee-chip'));
  assert.ok(!available.includes('历史文字'));
});

test('assignee highlighting never interprets a stored name as HTML', () => {
  const markup = assetDetailMarkup({ assignedTo: '<script>alert(1)</script>' });
  assert.ok(!markup.includes('<script>'));
  assert.ok(markup.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));
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

function qrFixture(overrides = {}) {
  const state = 'A'.repeat(43), redirectUri = 'http://asset.wfq.com/api/v1/auth/dingtalk/callback';
  return { state, redirectUri, expiresAt: Date.now() + 600_000,
    authorizationUrl: `https://login.dingtalk.com/oauth2/auth?${new URLSearchParams({ iframe: 'true', client_id: 'public-test-client', redirect_uri: redirectUri, response_type: 'code', scope: 'openid', prompt: 'consent', state })}`, ...overrides };
}
const qrSuccess = authorization => ({ success: true, redirectUrl: `${authorization.redirectUri}?${new URLSearchParams({ state: authorization.state, authCode: 'temporary-test-code' })}` });
function qrExports(mocks = {}, globals = {}) {
  return load('components/DingTalkQrLogin.tsx', mocks, false, { URL, Error, window: { location: { origin: 'http://asset.wfq.com' } }, ...globals });
}
test('QR iframe uses only the official embedded page and requires a same-origin callback', () => {
  const { qrFrameUrl } = qrExports(), authorization = qrFixture();
  assert.match(qrFrameUrl(authorization, 'http://asset.wfq.com'), /^https:\/\/login\.dingtalk\.com\/oauth2\/auth\?/);
  for (const origin of ['https://asset.wfq.com', 'http://asset.wfq.com:8081', 'http://172.168.30.202']) assert.throws(() => qrFrameUrl(authorization, origin), /域名、协议和端口/);
  for (const authorizationUrl of [authorization.authorizationUrl.replace('login.dingtalk.com', 'login.dingtalk.com.evil.example'), authorization.authorizationUrl.replace('iframe=true', 'iframe=false'), authorization.authorizationUrl.replace('scope=openid', 'scope=openid+corpid')]) {
    assert.throws(() => qrFrameUrl({ ...authorization, authorizationUrl }, 'http://asset.wfq.com'), /参数无效/);
  }
});
test('QR callback rejects state tampering, duplicate codes and redirects outside the configured callback', () => {
  const { qrCallbackUrl } = qrExports(), authorization = qrFixture(), message = qrSuccess(authorization);
  const accepted = new URL(qrCallbackUrl(message, authorization), 'http://asset.wfq.com');
  assert.equal(accepted.pathname, '/api/v1/auth/dingtalk/callback'); assert.equal(accepted.searchParams.get('state'), authorization.state);
  for (const redirectUrl of [message.redirectUrl.replace('asset.wfq.com', 'evil.example'), message.redirectUrl.replace('/callback?', '/callback/evil?'), message.redirectUrl.replace(authorization.state, 'B'.repeat(43)), `${message.redirectUrl}&state=${authorization.state}`, `${message.redirectUrl}&authCode=other`, `${message.redirectUrl}&code=other`, `${message.redirectUrl}&error=denied`, `${message.redirectUrl}#fragment`, 'javascript:alert(1)']) {
    assert.equal(qrCallbackUrl({ success: true, redirectUrl }, authorization), null);
  }
  for (const data of [null, 'untrusted', { success: false }, { success: 'true', redirectUrl: message.redirectUrl }]) assert.equal(qrCallbackUrl(data, authorization), null);
});
test('QR messages require the exact provider origin and current iframe and listeners are removed on close', () => {
  const authorization = qrFixture(), iframeWindow = {}, assigned = [], effects = [], listeners = new Map(); let cleanupCount = 0;
  const react = { useRef: () => ({ current: { contentWindow: iframeWindow } }), useState: initial => [initial, () => {}], useEffect: effect => effects.push(effect) };
  const browser = { location: { origin: 'http://asset.wfq.com', assign: value => assigned.push(value) }, setTimeout: () => 1, clearTimeout() {}, addEventListener: (name, listener) => listeners.set(name, listener), removeEventListener(name, listener) { assert.equal(listeners.get(name), listener); listeners.delete(name); cleanupCount++; } };
  const { DingTalkQrLogin } = qrExports({ react }, { window: browser });
  DingTalkQrLogin({ authorization, onRefresh() {} }); const cleanup = effects[0](), receive = listeners.get('message');
  for (const origin of ['https://evil.example', 'https://login.dingtalk.com.evil.example', 'http://login.dingtalk.com']) receive({ origin, source: iframeWindow, data: qrSuccess(authorization) });
  receive({ origin: 'https://login.dingtalk.com', source: {}, data: qrSuccess(authorization) }); assert.equal(assigned.length, 0);
  receive({ origin: 'https://login.dingtalk.com', source: iframeWindow, data: qrSuccess(authorization) });
  receive({ origin: 'https://login.dingtalk.com', source: iframeWindow, data: qrSuccess(authorization) }); assert.equal(assigned.length, 1);
  assert.match(assigned[0], /^\/api\/v1\/auth\/dingtalk\/callback\?/); cleanup(); assert.equal(cleanupCount, 1);
  receive({ origin: 'https://login.dingtalk.com', source: iframeWindow, data: qrSuccess(authorization) }); assert.equal(assigned.length, 1);
});
test('expired QR sessions cannot navigate even when a late provider message arrives', () => {
  const authorization = qrFixture({ expiresAt: Date.now() - 1 }), effects = [], changes = [], assigned = [], iframeWindow = {}; let receive, timeout;
  const react = { useRef: () => ({ current: { contentWindow: iframeWindow } }), useState: initial => [initial, value => changes.push(value)], useEffect: effect => effects.push(effect) };
  const browser = { location: { origin: 'http://asset.wfq.com', assign: value => assigned.push(value) }, setTimeout: callback => { timeout = callback; return 1; }, clearTimeout() {}, addEventListener: (_name, callback) => { receive = callback; }, removeEventListener() {} };
  qrExports({ react }, { window: browser }).DingTalkQrLogin({ authorization, onRefresh() {} }); effects[0](); timeout();
  receive({ origin: 'https://login.dingtalk.com', source: iframeWindow, data: qrSuccess(authorization) }); assert.deepEqual(assigned, []); assert.ok(changes.includes(true));
});
function treeElements(tree) {
  const result = [];
  React.Children.forEach(tree, child => { if (!child || typeof child !== 'object') return; result.push(child); result.push(...treeElements(child.props?.children)); });
  return result;
}
function loginHarness(api) {
  const states = ['', '', true], effects = [], assigned = []; let stateIndex = 0;
  const react = { useState(initial) { const index = stateIndex++; if (!(index in states)) states[index] = typeof initial === 'function' ? initial() : initial; return [states[index], value => { states[index] = typeof value === 'function' ? value(states[index]) : value; }]; }, useEffect: callback => effects.push(callback) };
  const { LoginPage } = load('components/LoginPage.tsx', { react, '../api': { api } }, false, { window: { location: { assign: value => assigned.push(value) } } });
  return { states, assigned, effects, render(props = {}) { stateIndex = 0; return treeElements(LoginPage({ loading: false, error: '', onLogin: async () => { throw new Error('Unexpected password login'); }, ...props })); } };
}
test('default DingTalk login preserves official desktop quick authorization without initializing a QR code', () => {
  let qrCalls = 0; const view = loginHarness({ beginDingtalkQr: async () => { qrCalls++; return qrFixture(); } });
  view.render().find(item => item.type === 'button' && item.props.children === '钉钉登录').props.onClick();
  assert.deepEqual(view.assigned, ['/api/v1/auth/dingtalk/login']); assert.equal(qrCalls, 0);
});
test('login page keeps password and official DingTalk login without duplicate QR controls or explanatory copy', () => {
  const tree = loginHarness({}).render();
  assert.equal(tree.filter(item => item.type === 'button').length, 2);
  assert.equal(tree.filter(item => item.type === 'form').length, 1);
  assert.ok(tree.some(item => item.type === 'h1' && item.props.children === 'Asset Management'));
  assert.ok(!tree.some(item => ['small', 'iframe'].includes(item.type)));
  assert.ok(!tree.some(item => item.props.children === '改用扫码登录'));
});
test('simplified login preserves password submission, error display and loading guards', () => {
  const view = loginHarness({}), submitted = [];
  const inputs = view.render().filter(item => item.type === 'input');
  inputs[0].props.onChange({ target: { value: 'admin' } }); inputs[1].props.onChange({ target: { value: 'test-password' } });
  let prevented = false;
  view.render({ onLogin: async (username, password) => { submitted.push({ username, password }); } }).find(item => item.type === 'form').props.onSubmit({ preventDefault() { prevented = true; } });
  assert.ok(prevented); assert.deepEqual(submitted, [{ username: 'admin', password: 'test-password' }]);
  const busy = view.render({ loading: true, error: '登录失败' });
  assert.ok(busy.some(item => item.props.role === 'alert' && item.props.children === '登录失败'));
  assert.ok(busy.filter(item => item.type === 'button').every(item => item.props.disabled));
  busy.find(item => item.type === 'button' && item.props.children === '钉钉登录').props.onClick(); assert.deepEqual(view.assigned, []);
});
test('disabled DingTalk login leaves the password form available', () => {
  const view = loginHarness({}); view.states[2] = false;
  const tree = view.render();
  assert.ok(tree.some(item => item.type === 'form'));
  assert.ok(!tree.some(item => item.type === 'button' && item.props.children === '钉钉登录'));
});
test('QR initialization is CSRF protected and never navigates to the generic authorization page', async () => {
  const calls = []; const { api } = load('api.ts', {}, false, { fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' } : qrFixture() }; } });
  await api.beginDingtalkQr(); assert.deepEqual(calls.map(call => call.url), ['/api/auth/csrf', '/api/v1/auth/dingtalk/qr']);
  assert.equal(calls[1].options.method, 'POST'); assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'csrf'); assert.equal(calls[1].options.credentials, 'include');
});

test('simultaneous writes share one CSRF request rather than overwriting its cookie', async () => {
  let reads = 0;
  const writes = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    if (url === '/api/auth/csrf') { reads++; await new Promise(resolve => setImmediate(resolve)); }
    else writes.push(options);
    return { ok: true, status: 200, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }) };
  } });
  await Promise.all([api.createProcurement({}), api.uploadMyAvatar(new File(['photo'], 'photo.png'))]);
  assert.equal(reads, 1);
  assert.equal(writes.length, 2);
  for (const options of writes) assert.equal(options.headers['X-CSRF-TOKEN'], 'csrf');
});

test('a rejected multipart write refreshes CSRF on the next explicit attempt', async () => {
  let reads = 0, writes = 0;
  const { api, ApiError } = load('api.ts', {}, false, { fetch: async (url, options) => {
    if (url === '/api/auth/csrf') { reads++; return { ok: true, status: 200, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: `csrf-${reads}` }) }; }
    writes++;
    return { ok: writes > 1, status: writes > 1 ? 200 : 403, json: async () => ({}) };
  } });
  await assert.rejects(api.uploadMyAvatar(new File(['photo'], 'photo.png')), error => error instanceof ApiError && error.status === 403);
  await api.uploadMyAvatar(new File(['photo'], 'photo.png'));
  assert.equal(writes, 2);
  assert.equal(reads, 2);
});

test('both quick return APIs send the version the user actually reviewed', async () => {
  const writes = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => {
    if (options.method === 'POST') writes.push({ url, options });
    return { ok: true, status: 200, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }) };
  } });
  await api.returnAsset(3, 7);
  await api.returnAssetByQrToken('qr-token', 9);
  assert.deepEqual(writes.map(call => call.options.headers['If-Match']), ['7', '9']);
});

test('procurement refresh also refreshes the asset choices after another person creates equipment', () => {
  let assetRefreshes = 0, orderRefreshes = 0;
  const { ProcurementPage } = load('components/ProcurementPage.tsx', {
    react: { useState: initial => [initial, () => {}], useRef: initial => ({ current: initial }), useEffect() {} },
    '../api': { api: {} }, './OperationPage': { canManageWorkflows: () => true },
    './HandoverPage': { requestKey: () => 'uuid' }, './AssetSupportPage': { SupplierSelect: () => null },
    './ConfirmDialog': { ConfirmDialog: () => null },
    './ManagementShared': { useRemote: () => ({ data: { items: [], canManage: true }, refresh: () => orderRefreshes++ }) },
  });
  const tree = ProcurementPage({ user: workflowUser(true), assets: [], onNotify() {}, onAssetsChanged: () => assetRefreshes++ });
  const refresh = tree.props.children[0].props.children[1].props.children[0];
  assert.equal(refresh.props.children, '刷新');
  refresh.props.onClick();
  assert.equal(orderRefreshes, 1);
  assert.equal(assetRefreshes, 1);
  assert.equal(typeof appMarkup('procurement').componentProps.ProcurementPage.onAssetsChanged, 'function');
});

const domainAsset = overrides => selfAsset({ category: { id: 1, name: '笔记本电脑', assetProfile: 'COMPUTER' },
  company: { id: 2, name: '公司' }, model: { id: 3, name: 'ThinkPad' }, location: { id: 4, name: '仓库' },
  status: { id: 5, name: '当前可用' }, checkedOut: false, purchasePrice: 3000, currentValue: 2000,
  boundDisplays: [], customParameters: [], relatedDevices: [], accessories: [], imageUrls: [],
  createdAt: '2026-10-23T10:00:00', updatedAt: '2026-10-23T10:00:00', ...overrides });

function assetTableMarkup(assets, loading = false) {
  const { AssetTable } = load('components/AssetTable.tsx', {
    './Icons': load('components/Icons.tsx'), './AssetImageLightbox': { AssetImageLightbox: () => null }, './BackToTop': { BackToTop: () => null },
  });
  return renderToStaticMarkup(React.createElement(AssetTable, { assets, loading, canEdit: true, canCreate: true, onSelect() {}, onEdit() {}, onClone() {} }));
}

test('table assignees use passive name tags without changing lifecycle, domain or stored names', () => {
  for (const name of ['已经领出', '维护中', '已报废']) {
    const assets = [domainAsset({ checkedOut: true, status: { name }, assignedTo: '  袁玉杰  ', domainJoinStatus: 'JOINED' })];
    const before = JSON.stringify(assets);
    const markup = assetTableMarkup(assets);
    assert.match(markup, /<td><span class="asset-assignee-tag" title="袁玉杰">袁玉杰<\/span><\/td>/);
    assert.match(markup, /domain-joined/);
    assert.equal(JSON.stringify(assets), before);
    assert.ok(!markup.includes('role="button"')); assert.ok(!markup.includes('tabindex='));
  }
});

test('unassigned table rows stay plain and muted instead of receiving a person tag', () => {
  for (const assignedTo of [null, undefined, '', '   ']) {
    const markup = assetTableMarkup([domainAsset({ assignedTo })]);
    assert.match(markup, /<span class="asset-assignee-empty">暂未领用<\/span>/);
    assert.ok(!markup.includes('asset-assignee-tag'));
  }
});

test('table assignee tags escape stored names and keep long names intact for the title', () => {
  const longName = '某某公司研发部门外协工程师张三';
  const longMarkup = assetTableMarkup([domainAsset({ assignedTo: longName })]);
  assert.ok(longMarkup.includes(`title="${longName}">${longName}</span>`));
  const malicious = '<img src=x onerror="alert(1)">';
  const markup = assetTableMarkup([domainAsset({ assignedTo: malicious })]);
  assert.ok(!markup.includes('<img src=x'));
  assert.match(markup, /&lt;img/);
});

test('domain status has its own aligned column between custody status and assignee; current value data is not rendered', () => {
  const assets = [domainAsset({ domainJoinStatus: 'JOINED', cpu: 'i7' }), domainAsset({ id: 2, domainJoinStatus: 'NOT_JOINED' })];
  const before = JSON.stringify(assets);
  const markup = assetTableMarkup(assets);
  assert.match(markup, /<th>状态<\/th><th scope="col" class="asset-domain-cell">入域状态<\/th><th>领用人<\/th>/);
  assert.ok(!markup.includes('当前价值')); assert.ok(!markup.includes('2,000.00'));
  assert.match(markup, /采购价格/); assert.match(markup, /3,000.00/);
  assert.match(markup, /domain-joined/); assert.match(markup, /domain-not_joined/);
  const cols = [...markup.matchAll(/<col class=/g)].length;
  for (const row of markup.split('<tbody>')[1].split('</tbody>')[0].matchAll(/<tr[^>]*>(.*?)<\/tr>/g)) assert.equal([...row[1].matchAll(/<td[ >]/g)].length, cols);
  assert.equal(JSON.stringify(assets), before);
});

test('legacy computers remain unknown, other equipment is not mislabeled as unjoined, and empty row colspan matches', () => {
  const markup = assetTableMarkup([domainAsset(), domainAsset({ id: 2, category: { name: '显示器', assetProfile: 'DISPLAY' } })]);
  assert.match(markup, /domain-unknown[^>]*>待确认/);
  assert.match(markup, /aria-label="入域状态不适用">—/);
  for (const assets of [[], [domainAsset({ purchasePrice: null })], [domainAsset({ cpu: 'i7', memory: '16G', storage: '512G' })]]) {
    const loading = assetTableMarkup(assets, true);
    const cols = [...loading.matchAll(/<col class=/g)].length;
    assert.match(loading, new RegExp(`<td colSpan="${cols}">`));
  }
});

test('domain filter works locally on computer records without changing the collection', () => {
  const assets = [domainAsset({ id: 1, domainJoinStatus: 'JOINED' }), domainAsset({ id: 2 }), domainAsset({ id: 3, category: { name: '显示器', assetProfile: 'DISPLAY' } })];
  const { matchesDomainStatus } = load('domainJoin.ts');
  assert.deepEqual(assets.filter(asset => matchesDomainStatus(asset, 'UNKNOWN')).map(asset => asset.id), [2]);
  assert.equal(assets.filter(asset => matchesDomainStatus(asset, '')).length, 3);
  const result = appMarkup('assets', {}, { name: 'list' }, { assets, domainFilter: 'JOINED' });
  assert.deepEqual(result.componentProps.AssetTable.assets.map(asset => asset.id), [1]);
  const filter = findNativeElement(result.tree, child => child.type === 'select' && child.props['aria-label'] === '筛选入域状态');
  assert.ok(filter); filter.props.onChange({ target: { value: 'NOT_JOINED' } });
  assert.ok(result.changes.some(change => change.index === 33 && change.value === 'NOT_JOINED'));
  assert.equal(assets.length, 3);
});

function assetFormHarness(asset, clone = false) {
  const states = [], effects = [], submissions = []; let index = 0;
  const react = { useState(initial) { const i = index++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], value => { states[i] = typeof value === 'function' ? value(states[i]) : value; }]; }, useRef: value => ({ current: value }), useEffect: effect => effects.push(effect) };
  const { AssetForm } = load('components/AssetForm.tsx', { react, '../api': { api: { domainStatuses: async () => load('domainJoin.ts').domainStatusOptions } }, './Icons': load('components/Icons.tsx'), './EditableCombobox': { EditableCombobox: () => null }, '../types': { lookupLabels: {} } });
  const lookups = [domainAsset().category, { id: 9, name: '显示器', assetProfile: 'DISPLAY' }].map(value => ({ ...value, type: 'CATEGORY' }));
  return { states, submissions, render() { index = 0; return AssetForm({ asset, clone, lookups, bindableAssets: [], assignees: [], canAssign: true, saving: false, createdLookup: null, onSave: async payload => submissions.push(payload) }); } };
}

test('computer edit selector preserves status, clones reset verification, and non-computer submissions clear only domain status', async () => {
  for (const [asset, clone, expected] of [[domainAsset({ domainJoinStatus: 'JOINED' }), false, 'JOINED'], [domainAsset({ domainJoinStatus: 'JOINED' }), true, 'UNKNOWN'], [domainAsset(), false, 'UNKNOWN']]) {
    const view = assetFormHarness(asset, clone);
    const tree = view.render();
    const select = findNativeElement(tree, child => child.type === 'select' && child.props['aria-label'] === '入域状态');
    assert.equal(select.props.value, expected);
    assert.equal(select.props.children.length, 3);
    assert.equal(select.props.disabled, clone);
    assert.equal(view.states[0].currentValue, 2000);
    if (!clone) select.props.onChange({ target: { value: 'NOT_JOINED' } });
    const form = findNativeElement(view.render(), child => child.type === 'form');
    await form.props.onSubmit({ preventDefault() {} });
    assert.equal(view.submissions[0].domainJoinStatus, clone ? 'UNKNOWN' : 'NOT_JOINED');
  }
  const display = assetFormHarness(domainAsset({ category: { id: 9, name: '显示器', assetProfile: 'DISPLAY' } }));
  const tree = display.render();
  assert.equal(findNativeElement(tree, child => child.type === 'select' && child.props['aria-label'] === '入域状态'), undefined);
  await findNativeElement(tree, child => child.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(display.submissions[0].domainJoinStatus, null);
});

test('domain detail remains separate from custody and retains current valuation', () => {
  const markup = assetDetailMarkup({ category: { name: '笔记本', assetProfile: 'COMPUTER' }, boundDisplays: [], domainJoinStatus: 'JOINED', currentValue: 2000 });
  assert.match(markup, /入域状态/); assert.match(markup, /domain-joined/);
  assert.match(markup, /领用人：袁玉杰/); assert.match(markup, /当前价值/); assert.match(markup, /2,000.00/);
});

test('foundation is an administrator-only page and never mounts its editor for employees', () => {
  assert.ok(menuLabels(sidebarMarkup({ canManageUsers: true })).includes('基础资料'));
  assert.ok(!menuLabels(sidebarMarkup()).includes('基础资料'));
  assert.ok(!appMarkup('foundation').rendered.includes('FoundationData'));
  assert.match(appMarkup('foundation').markup, /当前账号尚未获得此功能的访问权限/);
  assert.ok(appMarkup('foundation', { canManageUsers: true }).rendered.includes('FoundationData'));
});

test('foundation updates use CSRF and the version the administrator reviewed', async () => {
  const calls = [];
  const { api } = load('api.ts', {}, false, { fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' } : {} }; } });
  await api.updateDomainStatus('JOINED', { description: '核实后使用', sortOrder: 10, version: 4 });
  assert.equal(calls[1].url, '/api/foundation/domain-statuses/JOINED');
  assert.equal(calls[1].options.method, 'PUT'); assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'csrf');
  assert.equal(JSON.parse(calls[1].options.body).version, 4);
});

test('foundation editor saves only option metadata, blocks duplicate submits and refreshes versions after save', async () => {
  const states = { 4: 'DOMAIN' }, refs = [], effects = [], writes = [], notifications = []; let stateIndex = 0, refIndex = 0, resolveSave;
  const react = { useState(initial) { const i = stateIndex++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], value => { states[i] = typeof value === 'function' ? value(states[i]) : value; }]; }, useRef(initial) { const i = refIndex++; return refs[i] ||= { current: initial }; }, useEffect: effect => effects.push(effect) };
  const { FoundationData } = load('components/FoundationData.tsx', { react, './FoundationData.css': {}, './ModelOptions': { ModelOptions: () => null }, './AccessoryOptions': { AccessoryOptions: () => null }, './Icons': load('components/Icons.tsx'), '../api': { api: {
    domainStatuses: async () => load('domainJoin.ts').domainStatusOptions,
    updateDomainStatus: (code, payload) => { writes.push({ code, payload }); return new Promise(resolve => { resolveSave = () => resolve({ code, label: '已入域', ...payload, version: 1 }); }); },
  } } });
  const render = () => { stateIndex = 0; refIndex = 0; return FoundationData({ onNotify: message => notifications.push(message) }); };
  render(); effects[0](); await new Promise(resolve => setImmediate(resolve));
  const tree = render();
  assert.equal(treeElements(tree).filter(child => child.type === 'form').length, 3);
  const input = findNativeElement(tree, child => child.props['aria-label'] === '已入域的说明');
  input.props.onChange({ target: { value: '核验后才选择' } });
  const form = findNativeElement(render(), child => child.type === 'form');
  form.props.onSubmit({ preventDefault() {} }); form.props.onSubmit({ preventDefault() {} });
  assert.equal(writes.length, 1);
  assert.equal(writes[0].code, 'JOINED'); assert.equal(writes[0].payload.description, '核验后才选择');
  assert.deepEqual(Object.keys(writes[0].payload).sort(), ['description', 'sortOrder', 'version']);
  assert.ok(treeElements(render()).filter(child => child.type === 'input').every(child => child.props.disabled));
  resolveSave(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(states[0].find(option => option.code === 'JOINED').version, 1);
  assert.equal(notifications.length, 1);
});

function comboboxHarness(props = {}) {
  const states = [], refs = [], effects = [], calls = []; let stateIndex = 0, refIndex = 0, value = props.value || '', selectedId = props.selectedId ?? null;
  const react = { useState(initial) { const i = stateIndex++; if (!(i in states)) states[i] = initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef(initial) { return refs[refIndex++] ||= { current: initial }; }, useEffect: effect => effects.push(effect), useId: () => 'model-search' };
  const exports = load('components/EditableCombobox.tsx', { react, './Icons': load('components/Icons.tsx') }, false, { document: { activeElement: null, addEventListener() {}, removeEventListener() {} }, HTMLElement: class {}, window: { setTimeout: callback => callback() } });
  return { states, calls, effects, filter: exports.filterSuggestions, render() { stateIndex = 0; refIndex = 0; return treeElements(exports.EditableCombobox({ options: [], placeholder: '输入型号搜索', editable: true, suggestOnly: true, ...props, value, selectedId, onChange(next, id) { value = next; selectedId = id; calls.push([next, id]); } })); } };
}
function comboKey(key, extra = {}) { return { key, keyCode: 0, nativeEvent: { isComposing: false }, prevented: false, preventDefault() { this.prevented = true; }, stopPropagation() {}, ...extra }; }
const modelChoices = () => [{ id: 1, label: 'ThinkPad L13' }, { id: 2, label: 'ThinkPad T14' }, { id: 3, label: 'HUAWEI' }, { id: 4, label: '锐捷 RG-NBS3100-48GT4SFPV2' }];

test('model suggestions are input-triggered and never expand the full list on empty focus', () => {
  const view = comboboxHarness({ options: modelChoices() });
  view.render().find(item => item.type === 'input').props.onFocus();
  const empty = view.render(); assert.equal(empty.filter(item => item.props.role === 'option').length, 0);
  assert.equal(empty.find(item => item.type === 'input').props['aria-expanded'], false);
  assert.equal(empty.filter(item => item.props.className === 'combobox-arrow').length, 0);
  empty.find(item => item.type === 'input').props.onChange({ target: { value: '锐' } });
  const matches = view.render().filter(item => item.props.role === 'option'); assert.equal(matches.length, 1);
  assert.equal(matches[0].props.children[1].props.children, '锐捷 RG-NBS3100-48GT4SFPV2');
});
test('suggestions ignore case and harmless spacing, rank exact matches first and cap at eight without changing options', () => {
  const view = comboboxHarness(), options = Object.freeze([{ id: 1, label: 'prefix ThinkPad' }, { id: 2, label: 'ThinkPad T14' }, { id: 3, label: 'THINKPAD' }, ...Array.from({ length: 12 }, (_, i) => ({ id: i + 4, label: `ThinkPad ${i}` }))]);
  const result = view.filter(options, ' ｔｈｉｎｋ pad '); assert.equal(result.length, 8);
  assert.equal(result[0].id, 3); assert.equal(result[1].id, 2); assert.equal(options[0].id, 1);
  assert.equal(view.filter(options, '   ').length, 0);
});
test('arrows and Enter choose explicitly, Escape closes and Chinese composition cannot accidentally select or submit', () => {
  const view = comboboxHarness({ options: modelChoices() });
  view.render().find(item => item.type === 'input').props.onChange({ target: { value: 'think' } });
  const enter = comboKey('Enter'); view.render()[0].props.onKeyDown(enter); assert.ok(enter.prevented); assert.equal(view.calls.length, 1);
  view.render()[0].props.onKeyDown(comboKey('ArrowDown'));
  const input = view.render().find(item => item.type === 'input'); assert.equal(input.props['aria-activedescendant'], 'model-search-0');
  const composing = comboKey('Enter', { nativeEvent: { isComposing: true } }); view.render()[0].props.onKeyDown(composing);
  assert.equal(view.calls.length, 1); assert.equal(composing.prevented, false);
  view.render()[0].props.onKeyDown(comboKey('Enter', { keyCode: 229 })); assert.equal(view.calls.length, 1);
  view.render()[0].props.onKeyDown(comboKey('Enter')); assert.deepEqual(view.calls.at(-1), ['ThinkPad L13', 1]);
  view.render().find(item => item.type === 'input').props.onFocus(); view.render()[0].props.onKeyDown(comboKey('Escape'));
  assert.equal(view.render().find(item => item.type === 'input').props['aria-expanded'], false);
});
test('mouse selection retains the real option ID and no match retains free input without automatic data creation', () => {
  const view = comboboxHarness({ options: modelChoices() });
  view.render().find(item => item.type === 'input').props.onChange({ target: { value: 'huawei' } });
  view.render().find(item => item.props.role === 'option').props.onClick(); assert.deepEqual(view.calls.at(-1), ['HUAWEI', 3]);
  view.render().find(item => item.type === 'input').props.onChange({ target: { value: '新型号自填' } });
  assert.deepEqual(view.calls.at(-1), ['新型号自填', null]); assert.equal(view.render().filter(item => item.props.role === 'option').length, 0);
  assert.ok(view.render().some(item => item.props.children === '未找到匹配型号'));
});
test('unmanaged lookup dropdowns retain their current selection behavior and model fields retain the real ID without inline deletion', () => {
  const view = comboboxHarness({ options: modelChoices(), suggestOnly: false });
  view.render().find(item => item.props.className === 'combobox-arrow').props.onClick();
  assert.equal(view.render().filter(item => item.props.role === 'option').length, 4);
  const enter = comboKey('Enter'); view.render()[0].props.onKeyDown(enter); assert.equal(enter.prevented, false);
  const asset = domainAsset(), form = assetFormHarness(asset), model = treeElements(form.render()).find(item => item.props.placeholder === '输入型号搜索');
  assert.ok(model); assert.equal(model.props.selectedId, asset.model.id); assert.equal(model.props.value, asset.model.name);
  assert.equal(model.props.onDelete, undefined); assert.equal(model.props.allowCreate, undefined);
});
test('long model labels grow vertically instead of overflowing a fixed-height row', () => {
  const css = require('node:fs').readFileSync(path.join(frontend, 'src/styles.css'), 'utf8');
  const label = css.match(/\.combobox-option-label\s*\{([^}]+)\}/)[1];
  assert.match(label, /min-height:\s*39px/); assert.match(label, /overflow-wrap:\s*anywhere/);
  assert.ok(!/(?:^|;)\s*height:/.test(label));
});

function modelManagerHarness(api = {}, rows = [{ id: 4, name: '历史电脑型号', disabled: false, version: 2, assetCount: 3 }]) {
  const states = [rows, false, '', '', '', '', false, null], refs = [], effects = [], notices = []; let index = 0, refIndex = 0, refreshed = 0;
  const ApiError = class extends Error { constructor(message, status) { super(message); this.status = status; } };
  const react = { useState(initial) { const i = index++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef(initial) { return refs[refIndex++] ||= { current: initial }; }, useEffect: effect => effects.push(effect) };
  const { ModelOptions } = load('components/ModelOptions.tsx', { react, '../api': { api, ApiError }, './Icons': load('components/Icons.tsx'), './ConfirmDialog': { ConfirmDialog: () => null } }, false, { Error });
  return { states, effects, notices, ApiError, get refreshed() { return refreshed; }, render(revision = 0, type = 'MODEL') { index = 0; refIndex = 0; return treeElements(ModelOptions({ revision, type, onNotify: (text, kind) => notices.push({ text, kind }), onChanged: () => { refreshed++; } })); } };
}
test('model manager filters locally and offers availability controls without rename or deletion', () => {
  const view = modelManagerHarness({}, [{ id: 4, name: 'ThinkPad', disabled: false, version: 0, assetCount: 3 }, { id: 5, name: 'HUAWEI', disabled: true, version: 1, assetCount: 1 }]);
  const first = view.render(); assert.equal(first.filter(item => item.type === 'tr').length, 3);
  first.find(item => item.props['aria-label'] === '搜索设备型号').props.onChange({ target: { value: 'think' } });
  const filtered = view.render(); assert.equal(filtered.filter(item => item.type === 'tr').length, 2);
  const text = renderToStaticMarkup(filtered[0]); assert.match(text, /ThinkPad/); assert.ok(!text.includes('HUAWEI'));
  assert.ok(!text.includes('删除')); assert.ok(!text.includes('改名'));
});
test('model creation prevents double submissions and changes only the option list', async () => {
  const writes = []; let release;
  const view = modelManagerHarness({ createModelOption: name => { writes.push(name); return new Promise(resolve => { release = resolve; }); } });
  view.render().find(item => item.props['aria-label'] === '新型号名称').props.onChange({ target: { value: ' New Model ' } });
  const form = view.render().find(item => item.type === 'form'); form.props.onSubmit({ preventDefault() {} }); form.props.onSubmit({ preventDefault() {} });
  assert.deepEqual(writes, ['New Model']); assert.equal(view.refreshed, 0);
  release({ id: 9, name: 'New Model', disabled: false, version: 0, assetCount: 0 }); await new Promise(setImmediate);
  assert.equal(view.states[0].length, 2); assert.equal(view.states[0].find(row => row.id === 4).assetCount, 3);
  assert.equal(view.refreshed, 1); assert.equal(view.states[5], '');
});
test('disabling a used model requires confirmation and sends exactly the reviewed metadata version once', async () => {
  const writes = []; let release;
  const view = modelManagerHarness({ setModelAvailability: (...args) => { writes.push(args); return new Promise(resolve => { release = resolve; }); } });
  view.render().find(item => item.type === 'button' && item.props.children === '停用').props.onClick(); assert.equal(writes.length, 0);
  const dialog = view.render().find(item => item.props.title === '停用设备型号'); assert.match(dialog.props.description, /3 项资产保留原型号/);
  dialog.props.onConfirm(); dialog.props.onConfirm(); assert.deepEqual(writes, [[4, 2, true]]);
  release({ id: 4, name: '历史电脑型号', disabled: true, version: 3, assetCount: 3 }); await new Promise(setImmediate);
  assert.equal(view.states[0][0].version, 3); assert.equal(view.states[0][0].name, '历史电脑型号'); assert.equal(view.refreshed, 1);
});
test('canceling model disable has no writes and version conflicts reload without automatically retrying', async () => {
  let writes = 0, reads = 0;
  const view = modelManagerHarness({ setModelAvailability: async () => { writes++; throw new view.ApiError('状态已更新', 409); }, modelOptions: async () => { reads++; return [{ id: 4, name: '历史电脑型号', disabled: true, version: 4, assetCount: 3 }]; } });
  view.render().find(item => item.type === 'button' && item.props.children === '停用').props.onClick();
  view.render().find(item => item.props.title === '停用设备型号').props.onCancel(); assert.equal(writes, 0);
  view.render().find(item => item.type === 'button' && item.props.children === '停用').props.onClick();
  view.render().find(item => item.props.title === '停用设备型号').props.onConfirm(); await new Promise(setImmediate);
  assert.equal(writes, 1); assert.equal(reads, 1); assert.equal(view.states[0][0].version, 4); assert.equal(view.refreshed, 0);
});
test('failed model loads clear cached management rows rather than showing stale data', async () => {
  const view = modelManagerHarness({ modelOptions: async () => { throw new Error('无权访问'); } });
  view.render(); view.effects[0](); await new Promise(setImmediate);
  assert.equal(view.states[0].length, 0); assert.equal(view.states[1], false); assert.match(view.states[2], /无权访问/);
});
test('model management APIs reuse CSRF, remain scoped to metadata and refreshing options never reloads or updates assets', async () => {
  const calls = [], { api } = load('api.ts', {}, false, { fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf-model' } : [] }; } });
  await api.modelOptions(); await api.createModelOption('新型号'); await api.setModelAvailability(4, 7, true);
  assert.deepEqual(calls.map(call => call.url), ['/api/foundation/models', '/api/auth/csrf', '/api/foundation/models', '/api/foundation/models/4/availability']);
  assert.deepEqual(JSON.parse(calls[3].options.body), { version: 7, disabled: true }); assert.equal(calls[3].options.headers['X-CSRF-TOKEN'], 'csrf-model');
  let reads = 0; const page = appMarkup('foundation', { canManageUsers: true }, { name: 'list' }, { api: { listLookups: async () => { reads++; return []; } } });
  page.componentProps.FoundationData.onLookupsChanged(); await new Promise(setImmediate); assert.equal(reads, 1);
});

const accessoryChoice = (overrides = {}) => ({ id: 1, name: '鼠标', model: 'Logitech M185', specification: '无线 USB', disabled: false, version: 0, ...overrides });
function accessoryHarness(api = {}, overrides = {}) {
  const states = [], refs = [], changes = [], effects = []; let index = 0, refIndex = 0;
  const react = { useState(initial) { const i = index++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef(initial) { return refs[refIndex++] ||= { current: initial }; }, useEffect: effect => effects.push(effect) };
  const exports = load('components/AssetAccessories.tsx', { react, '../api': { api }, './Icons': load('components/Icons.tsx') }, false, { Error });
  const props = { relatedDevices: [], accessories: [], disabled: false, onChange: (relatedDevices, accessories) => { changes.push({ relatedDevices, accessories }); props.relatedDevices = relatedDevices; props.accessories = accessories; }, ...overrides };
  return { states, effects, changes, props, ...exports, render() { index = 0; refIndex = 0; effects.length = 0; return treeElements(exports.AssetAccessories(props)); } };
}
const accessoryButton = (items, text) => items.find(item => item.type === 'button' && React.Children.toArray(item.props.children).some(value => value === text));

test('accessory lookup waits for input, searches full-width case-insensitively, excludes disabled options and caps results without mutation', () => {
  const view = accessoryHarness();
  const choices = Object.freeze([accessoryChoice(), accessoryChoice({ id: 2, name: '停用鼠标', disabled: true }), ...Array.from({ length: 12 }, (_, i) => accessoryChoice({ id: i + 3 }))]);
  const before = JSON.stringify(choices);
  assert.equal(view.matchingAccessories(choices, ' ').length, 0);
  assert.equal(view.matchingAccessories(choices, 'ｌｏｇｉ tech').length, 8);
  assert.ok(!view.matchingAccessories(choices, '鼠标').some(row => row.id === 2));
  assert.equal(view.matchingAccessories(choices, 'USB').length, 8);
  assert.equal(JSON.stringify(choices), before);
});

test('new accessory selections are independent snapshots, not catalogue links or stock transactions', () => {
  const view = accessoryHarness(), option = accessoryChoice();
  const first = view.accessorySnapshot(option), second = view.accessorySnapshot(option);
  assert.deepEqual(JSON.parse(JSON.stringify(first)), { name: '鼠标', model: 'Logitech M185', specification: '无线 USB', quantity: 1, serialNumber: '', orderNumber: '' });
  option.name = '目录已改名'; first.quantity = 4;
  assert.equal(first.name, '鼠标'); assert.equal(second.quantity, 1); assert.equal(second.name, '鼠标');
  assert.equal(Object.hasOwn(first, 'id'), false);
});

test('asset editor keeps legacy and current accessory rows in their original collections when saving unrelated edits', async () => {
  const old = { name: '历史充电器', specification: '旧规格', quantity: 2 };
  const current = { name: '旧鼠标', model: '旧型号', specification: '旧规格', quantity: 3, serialNumber: '原SN', orderNumber: '原订单' };
  const asset = domainAsset({ relatedDevices: [current], accessories: [old] }), before = JSON.stringify(asset);
  const view = assetFormHarness(asset); const tree = view.render();
  const editor = treeElements(tree).find(item => item.type.name === 'AssetAccessories');
  assert.ok(editor); assert.equal(editor.props.relatedDevices.length, 1); assert.equal(editor.props.accessories.length, 1);
  treeElements(tree).find(item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  const sent = JSON.parse(JSON.stringify(view.submissions[0]));
  assert.deepEqual(sent.relatedDevices, [current]); assert.deepEqual(sent.accessories, [old]); assert.equal(JSON.stringify(asset), before);
});

test('accessory picker supports multiple selections across searches and changes the form only after Add', async () => {
  let reads = 0;
  const choices = [accessoryChoice(), accessoryChoice({ id: 2, name: '充电器', model: 'Lenovo', specification: '65W' })];
  const view = accessoryHarness({ accessoryChoices: async () => { reads++; return choices; } });
  accessoryButton(view.render(), '选择配件').props.onClick(); view.render(); view.effects[0](); await new Promise(setImmediate);
  const search = () => view.render().find(item => item.type === 'input' && item.props.type === 'search');
  search().props.onChange({ target: { value: '鼠标' } });
  view.render().find(item => item.props.type === 'checkbox').props.onChange({ target: { checked: true } });
  search().props.onChange({ target: { value: '65w' } });
  view.render().find(item => item.props.type === 'checkbox').props.onChange({ target: { checked: true } });
  assert.equal(view.changes.length, 0); assert.equal(reads, 1);
  view.render().find(item => item.type === 'button' && item.props.className === 'button primary').props.onClick();
  assert.equal(view.changes.length, 1); assert.equal(view.changes[0].relatedDevices.length, 2);
  assert.equal(view.changes[0].relatedDevices[1].quantity, 1); assert.equal(view.states[0], false);
});

test('accessory picker cancellation, Enter search and failed catalogue reads cannot submit or erase existing records', async () => {
  const existing = { name: '旧配件', model: '', specification: '', quantity: 1, serialNumber: '旧SN', orderNumber: '旧订单' };
  const view = accessoryHarness({ accessoryChoices: async () => { throw new Error('目录不可用'); } }, { relatedDevices: [existing] });
  accessoryButton(view.render(), '选择配件').props.onClick(); view.render(); view.effects[0](); await new Promise(setImmediate);
  const items = view.render(); assert.equal(items.find(item => item.props.role === 'alert').props.children, '目录不可用');
  assert.equal(items.find(item => item.type === 'button' && item.props.className === 'button primary').props.disabled, true);
  let prevented = 0; items.find(item => item.props.type === 'search').props.onKeyDown({ key: 'Enter', preventDefault() { prevented++; } });
  assert.equal(prevented, 1); accessoryButton(items, '取消').props.onClick();
  assert.equal(view.changes.length, 0); assert.equal(view.props.relatedDevices[0].serialNumber, '旧SN');
});

test('late accessory reads are ignored after picker closure and retry does not reuse stale options', async () => {
  let finish;
  const view = accessoryHarness({ accessoryChoices: () => new Promise(resolve => { finish = resolve; }) });
  accessoryButton(view.render(), '选择配件').props.onClick(); view.render(); const cleanup = view.effects[0]();
  accessoryButton(view.render(), '取消').props.onClick(); cleanup(); finish([accessoryChoice()]); await new Promise(setImmediate);
  assert.equal(view.states[1].length, 0); assert.equal(view.changes.length, 0);
});

test('accessory quantities and optional identifiers only update their own row and removing a row never deletes the catalogue', () => {
  const related = [{ name: '鼠标', model: '原型号', specification: '原规格', quantity: 1, serialNumber: '', orderNumber: '' }];
  const old = [{ name: '历史配件', specification: '原规格', quantity: 1 }];
  const view = accessoryHarness({}, { relatedDevices: related, accessories: old });
  view.render().find(item => item.props['aria-label'] === '鼠标 1 数量').props.onChange({ target: { value: '20000' } });
  assert.equal(view.props.relatedDevices[0].quantity, 9999); assert.equal(related[0].quantity, 1);
  view.render().find(item => item.props['aria-label'] === '鼠标 1 序列号').props.onChange({ target: { value: 'SN123' } });
  assert.equal(view.props.relatedDevices[0].serialNumber, 'SN123'); assert.equal(view.props.accessories[0].name, '历史配件');
  view.render().find(item => item.props['aria-label'] === '历史配件 原有配件 1 数量').props.onChange({ target: { value: '2' } });
  assert.equal(view.props.accessories[0].quantity, 2);
  view.render().find(item => item.props.title === '移除配件 鼠标').props.onClick();
  assert.equal(view.props.relatedDevices.length, 0); assert.equal(view.props.accessories.length, 1);
});

function accessoryManagerHarness(api = {}, rows = []) {
  const states = [rows, false, '', '', '', { name: '', model: '', specification: '' }, null, false, null], refs = [], effects = [], notices = []; let index = 0, refIndex = 0;
  const ApiError = class extends Error { constructor(message, status) { super(message); this.status = status; } };
  const react = { useState(initial) { const i = index++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef(initial) { return refs[refIndex++] ||= { current: initial }; }, useEffect: effect => effects.push(effect) };
  const { AccessoryOptions } = load('components/AccessoryOptions.tsx', { react, '../api': { api, ApiError }, './Icons': load('components/Icons.tsx'), './ConfirmDialog': { ConfirmDialog: () => null } }, false, { Error });
  return { states, effects, notices, ApiError, render() { index = 0; refIndex = 0; return treeElements(AccessoryOptions({ revision: 0, onNotify: (text, kind) => notices.push({ text, kind }) })); } };
}

test('accessory manager creates only catalogue metadata and blocks duplicate submissions', async () => {
  const writes = []; let finish;
  const view = accessoryManagerHarness({ createAccessoryOption: payload => { writes.push(payload); return new Promise(resolve => { finish = () => resolve(accessoryChoice(payload)); }); } });
  view.states[5] = { name: ' 鼠标 ', model: ' M185 ', specification: ' USB ' };
  const form = view.render().find(item => item.type === 'form'); form.props.onSubmit({ preventDefault() {} }); form.props.onSubmit({ preventDefault() {} });
  assert.equal(writes.length, 1); assert.deepEqual(JSON.parse(JSON.stringify(writes[0])), { name: '鼠标', model: 'M185', specification: 'USB' });
  finish(); await new Promise(setImmediate);
  assert.equal(view.states[0].length, 1); assert.equal(view.states[5].name, ''); assert.equal(view.notices.length, 1);
});

test('accessory manager does not replay stale edits, retains the draft and filters without more API reads', async () => {
  let writes = 0, reads = 0;
  const view = accessoryManagerHarness({ updateAccessoryOption: async () => { writes++; throw new view.ApiError('目录已更新', 409); }, accessoryOptions: async () => { reads++; return [accessoryChoice({ version: 8, name: '其他人已更新' })]; } }, [accessoryChoice({ version: 2 })]);
  accessoryButton(view.render(), '编辑').props.onClick(); view.states[5] = { ...view.states[5], name: '我的修改' };
  view.render().find(item => item.type === 'form').props.onSubmit({ preventDefault() {} }); await new Promise(setImmediate);
  assert.equal(writes, 1); assert.equal(reads, 1); assert.equal(view.states[6].version, 2); assert.equal(view.states[5].name, '我的修改');
  view.render().find(item => item.props['aria-label'] === '搜索配件目录').props.onChange({ target: { value: '找不到' } });
  assert.equal(view.render().filter(item => item.type === 'li').length, 0); assert.equal(reads, 1);
});

test('accessory catalogue API preserves CSRF and reviewed versions and choice reads remain read-only', async () => {
  const calls = [], { api } = load('api.ts', {}, false, { fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => url.endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'accessory-csrf' } : [] }; } });
  await api.accessoryChoices(); await api.accessoryOptions(); await api.createAccessoryOption({ name: '鼠标', model: 'M185', specification: 'USB' });
  await api.updateAccessoryOption(4, 7, { name: '键盘', model: 'K120', specification: 'USB' }); await api.setAccessoryAvailability(4, 8, true);
  assert.deepEqual(calls.map(call => call.url), ['/api/foundation/accessories/choices', '/api/foundation/accessories', '/api/auth/csrf', '/api/foundation/accessories', '/api/foundation/accessories/4', '/api/foundation/accessories/4/availability']);
  assert.equal(calls[0].options.method, undefined); assert.equal(calls[4].options.headers['X-CSRF-TOKEN'], 'accessory-csrf');
  assert.equal(JSON.parse(calls[4].options.body).version, 7); assert.deepEqual(JSON.parse(calls[5].options.body), { version: 8, disabled: true });
});

test('foundation accessory tab and refresh affect only catalogue metadata, not asset lists', () => {
  const states = { 4: 'ACCESSORY' }, effects = []; let index = 0;
  const react = { useState(initial) { const i = index++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef: initial => ({ current: initial }), useEffect: effect => effects.push(effect) };
  const { FoundationData } = load('components/FoundationData.tsx', { react, '../api': { api: {} }, './Icons': load('components/Icons.tsx'), './ModelOptions': { ModelOptions: () => null }, './AccessoryOptions': { AccessoryOptions: () => null } });
  const render = () => { index = 0; return treeElements(FoundationData({ onNotify() {}, onLookupsChanged() { assert.fail('Accessory refresh must not reload lookups or assets'); } })); };
  const items = render(); effects[0]();
  const editor = items.find(item => item.type.name === 'AccessoryOptions'); assert.ok(editor); assert.equal(editor.props.revision, 0);
  accessoryButton(items, '刷新').props.onClick(); assert.equal(render().find(item => item.type.name === 'AccessoryOptions').props.revision, 1);
});

test('CPU and asset-name inputs reuse model input-triggered suggestions and remove inline deletion without touching other fields', () => {
  const asset = domainAsset({ name: '历史电脑名称', cpu: '历史 CPU' }), view = assetFormHarness(asset), items = treeElements(view.render());
  const cpu = items.find(item => item.props.placeholder === '输入 CPU 搜索'), name = items.find(item => item.props.placeholder === '输入资产名称搜索');
  for (const field of [cpu, name]) {
    assert.ok(field); assert.equal(field.props.suggestOnly, true); assert.equal(field.props.editable, true); assert.equal(field.props.onDelete, undefined);
    assert.equal(field.props.allowCreate, undefined); assert.ok(!field.props.emptyText.includes('型号'));
  }
  assert.equal(cpu.props.value, '历史 CPU'); assert.equal(name.props.value, '历史电脑名称');
  cpu.props.onChange('x'.repeat(210)); name.props.onChange('y'.repeat(170));
  assert.equal(view.states[0].cpu.length, 200); assert.equal(view.states[0].name.length, 160);
  assert.equal(asset.name, '历史电脑名称'); assert.equal(asset.cpu, '历史 CPU');
});

test('managed text fields keep empty focus closed, support keyword matches and use field-specific no-match labels', () => {
  for (const [emptyText, options, query] of [
    ['未找到匹配 CPU', [{ id: 1, label: 'Intel i5-1155G7' }, { id: 2, label: 'AMD Ryzen 7' }], 'i5'],
    ['未找到匹配资产名称', [{ id: 1, label: '笔记本电脑' }, { id: 2, label: '显示器' }], '笔'],
  ]) {
    const view = comboboxHarness({ options, emptyText });
    view.render().find(item => item.type === 'input').props.onFocus(); assert.equal(view.render().filter(item => item.props.role === 'option').length, 0);
    view.render().find(item => item.type === 'input').props.onChange({ target: { value: query } }); assert.equal(view.render().filter(item => item.props.role === 'option').length, 1);
    view.render().find(item => item.type === 'input').props.onChange({ target: { value: '没有匹配内容' } });
    assert.equal(view.render().find(item => item.props.role === 'status').props.children, emptyText);
  }
});

test('CPU and asset-name managers reuse versioned availability controls and preserve type-specific field lengths', async () => {
  for (const [type, title, maximum] of [['CPU', 'CPU', 200], ['ASSET_NAME', '资产名称', 160]]) {
    const writes = [], view = modelManagerHarness({ setTextAvailability: async (...args) => { writes.push(args); return { id: 4, name: '历史值', disabled: true, version: 3, assetCount: 3 }; } });
    const initial = view.render(0, type); assert.equal(initial[0].props['aria-label'], `${title}管理`);
    assert.equal(initial.find(item => item.props.maxLength).props.maxLength, maximum);
    initial.find(item => item.type === 'button' && item.props.children === '停用').props.onClick(); assert.equal(writes.length, 0);
    const dialog = view.render(0, type).find(item => item.props.title === `停用${title}`); assert.ok(dialog);
    dialog.props.onConfirm(); await new Promise(setImmediate);
    assert.deepEqual(writes, [[type, 4, 2, true]]); assert.equal(view.refreshed, 1);
  }
});

test('CPU and asset-name manager creation only saves metadata to the chosen category', async () => {
  for (const type of ['CPU', 'ASSET_NAME']) {
    const writes = [], view = modelManagerHarness({ createTextOption: async (...args) => { writes.push(args); return { id: 9, name: '新值', disabled: false, version: 0, assetCount: 0 }; } });
    view.states[5] = ' 新值 ';
    view.render(0, type).find(item => item.type === 'form').props.onSubmit({ preventDefault() {} }); await new Promise(setImmediate);
    assert.deepEqual(writes, [[type, '新值']]); assert.equal(view.states[0].length, 2); assert.equal(view.states[5], '');
  }
});

test('CPU and asset-name APIs preserve CSRF and reviewed metadata versions without asset mutation', async () => {
  const calls = [], { api } = load('api.ts', {}, false, { fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => url.endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'text-csrf' } : [] }; } });
  for (const type of ['CPU', 'ASSET_NAME']) {
    await api.textOptions(type); await api.createTextOption(type, '新值'); await api.setTextAvailability(type, 5, 9, true);
  }
  assert.deepEqual(calls.map(call => call.url), ['/api/foundation/cpus', '/api/auth/csrf', '/api/foundation/cpus', '/api/foundation/cpus/5/availability', '/api/foundation/asset-names', '/api/foundation/asset-names', '/api/foundation/asset-names/5/availability']);
  for (const index of [3, 6]) {
    assert.deepEqual(JSON.parse(calls[index].options.body), { version: 9, disabled: true }); assert.equal(calls[index].options.headers['X-CSRF-TOKEN'], 'text-csrf');
  }
});

test('foundation text tabs refresh the correct manager and reset component identity on category switches', () => {
  const states = { 4: 'CPU' }, effects = []; let index = 0;
  const react = { useState(initial) { const i = index++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef: initial => ({ current: initial }), useEffect: effect => effects.push(effect) };
  const { FoundationData } = load('components/FoundationData.tsx', { react, '../api': { api: {} }, './Icons': load('components/Icons.tsx'), './ModelOptions': { ModelOptions: () => null }, './AccessoryOptions': { AccessoryOptions: () => null } });
  const render = () => { index = 0; return treeElements(FoundationData({ onNotify() {} })); };
  const items = render(); effects[0]();
  const cpu = items.find(item => item.type.name === 'ModelOptions'); assert.equal(cpu.props.type, 'CPU'); assert.equal(cpu.key, 'CPU');
  accessoryButton(items, '刷新').props.onClick(); assert.equal(render().find(item => item.type.name === 'ModelOptions').props.revision, 1);
  accessoryButton(render(), '资产名称').props.onClick(); const name = render().find(item => item.type.name === 'ModelOptions');
  assert.equal(name.props.type, 'ASSET_NAME'); assert.notEqual(name.key, cpu.key);
});

function bundleFormHarness(options = {}) {
  const states = [], refs = [], submissions = [], ordinary = [], continued = [];
  let stateIndex = 0, refIndex = 0;
  class ApiError extends Error { constructor(message, status) { super(message); this.status = status; } }
  const react = { useState(initial) { const i = stateIndex++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; }, useRef(initial) { return refs[refIndex++] ||= { current: initial }; }, useEffect() {} };
  const lookups = [domainAsset().category, { id: 9, name: '显示器', assetProfile: 'DISPLAY' }].map(value => ({ ...value, type: 'CATEGORY' }));
  lookups.push({ id: 22, type: 'STATUS', name: '当前可用' });
  const { AssetForm } = load('components/AssetForm.tsx', { react, '../api': { ApiError, api: { getAssetBundle: async key => options.recover(key) } }, './Icons': load('components/Icons.tsx'), './EditableCombobox': { EditableCombobox: () => null }, '../types': { lookupLabels: {} } }, false, { window: { confirm: () => true } });
  const props = { asset: domainAsset(), clone: false, lookups, bindableAssets: [], assignees: [], canAssign: true, saving: false, createdLookup: null, canCreateRelated: true,
    onSave: async payload => ordinary.push(payload), onCancel() {}, onDeleteLookup() {}, onNewLookup() {}, onContinueBundle: defaults => continued.push(defaults),
    onSaveBundle: async payload => { submissions.push(payload); if (options.save) return options.save(payload, ApiError); return { computer: domainAsset(), displays: [{ ...domainAsset(), id: 91, assetTag: '202610280091', name: '显示器' }], replayed: false }; }, ...options.props };
  return { states, submissions, ordinary, continued, props, ApiError, render() { stateIndex = 0; refIndex = 0; return AssetForm(props); } };
}
function bundlePayload() {
  const view = bundleFormHarness(); view.render();
  return { ...view.states[0], boundDisplayIds: [] };
}
function addBundleDisplay(view) {
  accessoryButton(treeElements(view.render()), '新建显示器').props.onClick();
  return treeElements(view.render()).find(item => item.type.name === 'NewDisplayEditor');
}
test('bundle builder shares only common defaults and preserves independent monitor details', () => {
  const { buildAssetBundle } = load('assetBundle.ts');
  const pc = { ...bundlePayload(), companyId: 11, locationId: 12, ownershipDepartment: '研发', statusId: 13, checkedOut: true, assignedTo: '本人', assignedUserId: 10, imageUrl: 'data:pc', imageUrls: ['data:pc'], boundDisplayIds: [8] };
  const monitor = { ...bundlePayload(), companyId: 21, locationId: 22, ownershipDepartment: '仓库', manufacturerSerialNumber: 'MONITOR-SN', purchasePrice: 629, modelName: 'AOC27', imageUrl: 'data:display', imageUrls: ['data:display'] };
  const inherited = buildAssetBundle(pc, [{ id: 'draft', inheritCommon: true, data: monitor }], [{ id: 8, version: 4 }], 'key', { id: 1 });
  assert.equal(inherited.computerId, 1); assert.equal(inherited.computerVersion, pc.version); assert.equal(inherited.computer.version, undefined);
  assert.equal(inherited.computer.imageUrl, ''); assert.equal(inherited.displays[0].imageUrl, ''); assert.equal(inherited.displays[0].imageUrls[0], 'data:display');
  assert.equal(inherited.displays[0].companyId, 11); assert.equal(inherited.displays[0].assignedUserId, 10);
  assert.equal(inherited.displays[0].manufacturerSerialNumber, 'MONITOR-SN'); assert.equal(inherited.displays[0].purchasePrice, 629);
  assert.equal(inherited.existingDisplays[0].version, 4); assert.equal(inherited.displays[0].boundComputerId, null); assert.equal(inherited.displays[0].domainJoinStatus, null);
  const independent = buildAssetBundle({ ...pc, boundDisplayIds: [] }, [{ id: 'draft', inheritCommon: false, data: monitor }], [], 'key', { id: 1 }, true);
  assert.equal(independent.cloneSourceId, 1); assert.equal(independent.computerId, undefined); assert.equal(independent.displays[0].companyId, 21);
  assert.equal(monitor.imageUrl, 'data:display');
});
test('bundle builder refuses missing reviewed monitors and oversized photo submissions', () => {
  const { buildAssetBundle } = load('assetBundle.ts'); const pc = bundlePayload();
  assert.throws(() => buildAssetBundle({ ...pc, boundDisplayIds: [9] }, [], [], 'key'), /信息不完整/);
  assert.throws(() => buildAssetBundle({ ...pc, imageUrls: ['x'.repeat(10 * 1024 * 1024)] }, [], [], 'key'), /总量过大/);
});
test('bundle request IDs work on HTTP intranet browsers without randomUUID', () => {
  const { newBundleKey } = load('assetBundle.ts', {}, false, { crypto: { getRandomValues: bytes => { bytes.fill(17); return bytes; } } });
  assert.match(newBundleKey(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
test('adding and removing monitors only edits drafts; ordinary asset save remains available', async () => {
  const view = bundleFormHarness(); const editor = addBundleDisplay(view);
  assert.equal(editor.props.draft.inheritCommon, true); assert.equal(editor.props.draft.data.manufacturerSerialNumber, '');
  assert.equal(view.submissions.length, 0); editor.props.onRemove();
  await findNativeElement(view.render(), item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(view.ordinary.length, 1); assert.equal(view.submissions.length, 0);
  const restricted = bundleFormHarness({ props: { canCreateRelated: false } });
  assert.equal(accessoryButton(treeElements(restricted.render()), '新建显示器'), undefined);
});
test('bundle save waits for all monitor photos then shows independent and batch label links', async () => {
  const view = bundleFormHarness(); const editor = addBundleDisplay(view);
  editor.props.onProcessing(editor.props.draft.id, true);
  await findNativeElement(view.render(), item => item.type === 'form').props.onSubmit({ preventDefault() {} }); assert.equal(view.submissions.length, 0);
  editor.props.onProcessing(editor.props.draft.id, false);
  await findNativeElement(view.render(), item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  const result = treeElements(view.render()), links = result.filter(item => item.type === 'a');
  assert.equal(links.length, 3); assert.ok(links.some(item => item.props.href === '/label-print?assetIds=1,91'));
  accessoryButton(result, '继续录入下一套').props.onClick();
  assert.deepEqual(Object.keys(view.continued[0]).sort(), ['categoryId', 'companyId', 'locationId', 'ownershipDepartment']);
});
test('network failure freezes the exact bundle for retry and duplicate clicks are blocked', async () => {
  let attempt = 0, resolveSave;
  const view = bundleFormHarness({ save: async () => { if (!attempt++) throw new TypeError('网络中断'); return new Promise(resolve => { resolveSave = () => resolve({ computer: domainAsset(), displays: [], replayed: true }); }); } });
  addBundleDisplay(view); await findNativeElement(view.render(), item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(findNativeElement(view.render(), item => item.type === 'fieldset').props.disabled, true);
  const retry = accessoryButton(treeElements(view.render()), '重试保存整套'); const firstRetry = retry.props.onClick(); retry.props.onClick();
  assert.equal(view.submissions.length, 2); assert.equal(view.submissions[0], view.submissions[1]);
  resolveSave(); await firstRetry; await new Promise(resolve => setImmediate(resolve));
  assert.equal(findNativeElement(view.render(), item => item.type === 'form'), undefined);
});
test('unknown bundle result can be recovered and a 404 does not unlock a possibly in-flight request', async () => {
  let found = false, expectedKey;
  const view = bundleFormHarness({ save: async payload => { expectedKey = payload.requestKey; throw new TypeError('网络中断'); }, recover: async key => { assert.equal(key, expectedKey); if (!found) throw new view.ApiError('未完成', 404); return { computer: domainAsset(), displays: [], replayed: true }; } });
  addBundleDisplay(view); await findNativeElement(view.render(), item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  await accessoryButton(treeElements(view.render()), '查询保存结果').props.onClick(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(findNativeElement(view.render(), item => item.type === 'fieldset').props.disabled, true);
  found = true; await accessoryButton(treeElements(view.render()), '查询保存结果').props.onClick(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(findNativeElement(view.render(), item => item.type === 'form'), undefined);
});
test('known validation conflicts preserve editable drafts and next-set defaults clear device identity and photos', async () => {
  const view = bundleFormHarness({ save: async (_payload, ApiError) => { throw new ApiError('SN已存在', 409); } });
  addBundleDisplay(view); await findNativeElement(view.render(), item => item.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(findNativeElement(view.render(), item => item.type === 'fieldset').props.disabled, false);
  assert.equal(treeElements(view.render()).filter(item => item.type.name === 'NewDisplayEditor').length, 1);
  const next = bundleFormHarness({ props: { asset: null, entryDefaults: { companyId: 11, ownershipDepartment: '研发', locationId: 12, categoryId: domainAsset().category.id } } }); next.render();
  assert.equal(next.states[0].companyId, 11); assert.equal(next.states[0].assignedTo, ''); assert.equal(next.states[0].manufacturerSerialNumber, '');
  assert.equal(next.states[0].imageUrls.length, 0); assert.equal(next.states[0].checkedOut, false);
});
test('existing monitor selection is search-first and retains already selected monitors', () => {
  const monitor = { ...domainAsset(), id: 9, assetTag: '202610280009', name: '显示器', category: { id: 9, name: '显示器', assetProfile: 'DISPLAY' }, boundComputer: null };
  const view = bundleFormHarness({ props: { bindableAssets: [monitor] } });
  assert.equal(treeElements(view.render()).filter(item => item.type === 'input' && item.props.type === 'checkbox').length, 0);
  accessoryButton(treeElements(view.render()), '选择已有显示器').props.onClick();
  findNativeElement(view.render(), item => item.type === 'input' && item.props.type === 'search').props.onChange({ target: { value: '202610280009' } });
  const checkbox = findNativeElement(view.render(), item => item.type === 'input' && item.props.type === 'checkbox'); checkbox.props.onChange({ target: { checked: true } });
  findNativeElement(view.render(), item => item.type === 'input' && item.props.type === 'search').props.onChange({ target: { value: '' } });
  assert.equal(findNativeElement(view.render(), item => item.type === 'input' && item.props.type === 'checkbox').props.checked, true);
});
test('bundle mutations reuse CSRF and recovery stays read-only', async () => {
  const calls = []; const { api } = load('api.ts', {}, false, { fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => url === '/api/auth/csrf' ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' } : {} }; } });
  await api.saveAssetBundle({ requestKey: 'key' }); await api.getAssetBundle('key');
  assert.equal(calls[1].url, '/api/assets/bundles'); assert.equal(calls[1].options.method, 'POST'); assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'csrf');
  assert.equal(calls[2].url, '/api/assets/bundles/key'); assert.equal(calls[2].options.method, undefined);
});
test('Safari missing FileReader reports a clear restriction without bypassing browser security', async () => {
  const { prepareImage } = load('components/AssetForm.tsx', { '../types': { lookupLabels: {} } });
  await assert.rejects(prepareImage({ type: 'image/jpeg', size: 10 }), /当前浏览器禁止读取照片.*锁定模式/);
});
function labelHarness(search) {
  const states = []; let index = 0, prints = 0;
  const react = { useMemo: callback => callback(), useEffect() {}, useState(initial) { const i = index++; if (!(i in states)) states[i] = initial; return [states[i], next => { states[i] = typeof next === 'function' ? next(states[i]) : next; }]; } };
  const { AssetLabelPrintPreview } = load('components/AssetLabelPrintPreview.tsx', { react }, false, { window: { location: { search }, print: () => prints++ } });
  return { render() { index = 0; return AssetLabelPrintPreview(); }, prints: () => prints };
}
test('batch labels require every authorized image to load before printing', () => {
  const view = labelHarness('?assetIds=1,2,3'); const items = treeElements(view.render()), images = items.filter(item => item.type === 'img');
  assert.deepEqual(images.map(item => item.props.src), ['/api/assets/1/qr', '/api/assets/2/qr', '/api/assets/3/qr']);
  const print = () => treeElements(view.render()).find(item => item.type === 'button');
  assert.equal(print().props.disabled, true); images[0].props.onLoad(); images[1].props.onLoad(); assert.equal(print().props.disabled, true);
  images[2].props.onLoad(); assert.equal(print().props.disabled, false); print().props.onClick(); assert.equal(view.prints(), 1);
  images[1].props.onError(); assert.equal(print().props.disabled, true);
});
test('batch label URL validation rejects invalid or duplicate IDs and preserves single-label download', () => {
  for (const search of ['?assetIds=', '?assetIds=1,1', '?assetIds=1,x', '?assetIds=1,-2', '?assetIds=1,9007199254740992', '?assetIds=' + Array.from({ length: 30 }, (_, i) => i + 1).join(',')]) {
    assert.equal(treeElements(labelHarness(search).render()).filter(item => item.type === 'img').length, 0);
  }
  const tree = treeElements(labelHarness('?assetId=9&assetTag=202610280009').render());
  assert.equal(tree.find(item => item.type === 'a').props.download, 'asset-202610280009-label.png');
  assert.equal(tree.find(item => item.type === 'img').props.src, '/api/assets/9/qr');
});
