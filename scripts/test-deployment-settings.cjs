const assert = require('node:assert/strict');
const { before, test } = require('node:test');
const { createRequire } = require('node:module');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../frontend');
const localRequire = createRequire(path.join(root, 'package.json'));
let code;

before(async () => {
  const { build } = await import(pathToFileURL(localRequire.resolve('vite')).href);
  const result = await build({ root, configFile: false, logLevel: 'silent', build: {
    ssr: true, write: false, minify: false, rollupOptions: {
      input: path.join(root, 'src/components/DeploymentIntegration.tsx'),
      external: (_id, importer) => Boolean(importer), output: { format: 'cjs' },
    },
  } });
  code = result.output.find(item => item.type === 'chunk').code;
});

const credential = { id: 'test-id', name: 'Test AD', createdAt: '2026-01-01T00:00:00Z', expiresAt: '2027-01-01T00:00:00Z', revokedAt: null, active: true };
const status = { enabled: false, secureRequest: true, allowHttp: false, endpoint: '/api/integrations/ad-deployment/v1/registrations', lastReceivedAt: null };
function harness(api = {}, overrides = {}) {
  const states = { 0: status, 1: [credential], 2: { items: [], totalElements: 0, totalPages: 0 }, 4: false, ...overrides };
  let index = 0;
  const effects = [], notices = [];
  const react = { useState(initial) {
    const key = index++;
    if (!(key in states)) states[key] = initial;
    return [states[key], next => { states[key] = typeof next === 'function' ? next(states[key]) : next; }];
  }, useEffect(effect) { effects.push(effect); } };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, Date, Error, navigator: {}, require(id) {
    if (id === 'react') return react;
    if (id === '../api') return { api };
    if (id.endsWith('.css')) return {};
    return localRequire(id);
  } });
  const flatten = (node, list = []) => {
    if (!node || typeof node !== 'object') return list;
    if (Array.isArray(node)) { node.forEach(child => flatten(child, list)); return list; }
    list.push(node); flatten(node.props?.children, list); return list;
  };
  return { states, effects, notices, render() {
    index = 0;
    return flatten(module.exports.DeploymentIntegration({ onNotify: (...args) => notices.push(args) }));
  } };
}
const button = (tree, label) => tree.find(node => node.type === 'button' && node.props.children === label);

test('disabled integration does not claim a successful connection or mutate on page load', () => {
  const ui = harness(); const tree = ui.render();
  assert.ok(tree.some(node => node.type === 'dd' && node.props.children === '未启用'));
  assert.ok(tree.some(node => node.type === 'p' && node.props.children === '尚无登记记录，未验证 AD 发送端连通性。'));
  assert.equal(tree.filter(node => node.type === 'input' && node.props.type === 'checkbox').length, 0);
});

test('confirmed internal HTTP enables credential issuance without HTTPS', () => {
  const ui = harness({}, { 0: { ...status, enabled: true, secureRequest: false, allowHttp: true } });
  assert.equal(button(ui.render(), '创建凭据').props.disabled, false);
});

test('web settings require explicit HTTP acknowledgement and save without env or restart', async () => {
  const calls = [];
  const ui = harness({ updateDeploymentSettings: async (...args) => { calls.push(args); return { ...status, enabled: true, allowHttp: true }; } });
  button(ui.render(), '接入设置').props.onClick();
  let tree = ui.render();
  tree.find(node => node.type === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } });
  tree = ui.render(); tree.find(node => node.type === 'select').props.onChange({ target: { value: 'http' } });
  tree = ui.render(); assert.equal(button(tree, '保存设置').props.disabled, true); assert.deepEqual(calls, []);
  const checks = tree.filter(node => node.type === 'input' && node.props.type === 'checkbox');
  checks[1].props.onChange({ target: { checked: true } });
  tree = ui.render(); assert.equal(button(tree, '保存设置').props.disabled, false);
  button(tree, '保存设置').props.onClick(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, [[true, true, true]]);
  assert.equal(ui.states[13], null); assert.equal(ui.states[0].allowHttp, true);
});

test('insecure backend request prevents credential issuance', () => {
  const ui = harness({}, { 0: { ...status, secureRequest: false } });
  assert.equal(button(ui.render(), '创建凭据').props.disabled, true);
});

test('issuing token validates fields and keeps token only until explicit close', async () => {
  const calls = [];
  const ui = harness({ issueDeploymentCredential: async (...args) => { calls.push(args); return { credential, token: 'synthetic-test-token' }; } });
  let tree = ui.render();
  await tree.find(node => node.type === 'form').props.onSubmit({ preventDefault() {} });
  // React handlers deliberately discard the promise; flush the pending API microtask.
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 1); assert.equal(calls[0][0], 'AD 装机平台');
  assert.ok(new Date(calls[0][1]).getTime() > Date.now());
  tree = ui.render();
  assert.equal(tree.find(node => node.type === 'textarea').props.value, 'synthetic-test-token');
  button(tree, '已保存，关闭').props.onClick();
  assert.equal(ui.render().some(node => node.type === 'textarea'), false);
});

test('invalid lifetime never calls issuance API', async () => {
  let calls = 0;
  const ui = harness({ issueDeploymentCredential: async () => { calls++; } }, { 8: 367 });
  ui.render().find(node => node.type === 'form').props.onSubmit({ preventDefault() {} });
  await Promise.resolve(); assert.equal(calls, 0); assert.equal(ui.notices.length, 1);
});

test('revocation requires confirmation and does not delete records', async () => {
  const calls = [];
  const ui = harness({ revokeDeploymentCredential: async id => { calls.push(id); } });
  button(ui.render(), '撤销').props.onClick();
  assert.deepEqual(calls, []);
  let tree = ui.render(); assert.ok(tree.some(node => node.props?.role === 'alertdialog'));
  button(tree, '取消').props.onClick(); assert.deepEqual(calls, []);
  button(ui.render(), '撤销').props.onClick(); tree = ui.render();
  button(tree, '确认撤销').props.onClick(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ['test-id']); assert.equal(ui.states[11], null);
});

test('conflict detail retains differences and has no overwrite control', () => {
  const entry = { externalRegistrationId: 'ad:test:v1', assetTag: '001234567890', assetId: 7, credentialId: credential.id,
    result: 'configuration_conflict', declaredOperator: { id: 'op', displayName: 'Operator' }, warnings: [],
    differences: [{ field: 'cpu', existing: 'Old CPU', incoming: 'New CPU' }] };
  const ui = harness({}, { 2: { items: [entry], totalElements: 1, totalPages: 1 } });
  button(ui.render(), '查看详情').props.onClick();
  const tree = ui.render(); assert.ok(tree.some(node => node.type === 'strong' && node.props.children === 'CPU'));
  assert.equal(button(tree, '覆盖更新'), undefined);
  assert.ok(tree.some(node => node.type === 'td' && node.props.children === '001234567890'));
});

test('load failure is visible without showing stale credentials', async () => {
  const ui = harness({ getDeploymentStatus: async () => { throw new Error('Unavailable'); }, listDeploymentCredentials: async () => [], listDeploymentRegistrations: async () => ({ items: [] }) });
  ui.render(); ui.effects[0]();
  await new Promise(resolve => setImmediate(resolve));
  const tree = ui.render(); assert.ok(tree.some(node => node.props?.role === 'alert' && node.props.children === 'Unavailable'));
  assert.equal(button(tree, '撤销'), undefined);
});
