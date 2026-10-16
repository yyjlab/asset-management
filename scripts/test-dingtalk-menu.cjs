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
  compiled = Object.fromEntries(await Promise.all(['App.tsx', 'components/Sidebar.tsx', 'components/Icons.tsx'].map(async file => {
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

function load(file, mocks = {}, collapsed = false) {
  const module = { exports: {} };
  const browser = { location: { pathname: '/' }, localStorage: { getItem: () => String(collapsed) } };
  vm.runInNewContext(compiled[file], {
    module, exports: module.exports, window: browser,
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

test('DingTalk employees see all five non-settings menus even without permissions', () => {
  assert.deepEqual(menuLabels(sidebarMarkup({ dingtalkLogin: true, permissions: [] })), ['驾驶舱', '资产', '用户', '日志', '数据']);
});

test('collapsed DingTalk menus retain their labels and accessible names', () => {
  const markup = sidebarMarkup({ dingtalkLogin: true }, true);
  assert.match(markup, /sidebar collapsed/);
  for (const label of ['驾驶舱', '资产', '用户', '日志', '数据']) assert.ok(markup.includes(`aria-label="${label}"`));
  assert.ok(!markup.includes('aria-label="设置"'));
});

test('password login retains existing permission-based menus', () => {
  assert.deepEqual(menuLabels(sidebarMarkup()), ['驾驶舱', '资产']);
  assert.deepEqual(menuLabels(sidebarMarkup({ permissions: [] })), ['驾驶舱']);
  assert.deepEqual(menuLabels(sidebarMarkup({ permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '资产', '数据']);
});

test('administrator keeps the settings menu with either login method', () => {
  for (const dingtalkLogin of [true, false]) {
    assert.deepEqual(menuLabels(sidebarMarkup({ canManageUsers: true, dingtalkLogin, permissions: ['ASSET_VIEW', 'ASSET_EXPORT'] })), ['驾驶舱', '资产', '用户', '日志', '设置', '数据']);
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
  assert.deepEqual(sections, ['dashboard', 'assets', 'users', 'logs', 'transfer']);
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
  const componentNames = ['AssetDetail', 'AssetForm', 'AssetTable', 'LookupModal', 'LoginPage', 'UserManagement', 'SystemSettings', 'ConfirmDialog', 'AuditLogPage', 'AvatarEditor', 'AssetLabelPrintPreview', 'ComputerDashboard', 'AssetTransfer'];
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
