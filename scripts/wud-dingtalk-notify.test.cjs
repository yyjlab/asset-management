'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { createHmac } = require('node:crypto');
const { signedWebhook, message, notify, main } = require('./wud-dingtalk-notify.cjs');

const now = Date.UTC(2026, 9, 15, 16, 0, 0);
const webhook = 'https://oapi.dingtalk.com/robot/send?access_token=example-token';
const secret = 'example-secret-not-a-real-credential';
const container = (phase = 'post') => ({
    name: 'asset-management-backend-1',
    image: { name: 'recovery-uat/be-asset', tag: { value: 'test9' } },
    result: { tag: 'test12', hook: { phase } },
    updateKind: { localValue: 'test9', remoteValue: 'test12' },
});
const environment = (phase) => ({
    WUD_DINGTALK_ENABLED: 'true', WUD_DINGTALK_WEBHOOK: webhook,
    WUD_DINGTALK_SECRET: secret, container_json: JSON.stringify(container(phase)),
});
const ok = async () => ({ ok: true, json: async () => ({ errcode: 0 }) });

test('signature uses timestamp, newline, HMAC-SHA256 and single URL encoding', () => {
    const url = signedWebhook(`${webhook}&sign=stale&timestamp=1#unused`, secret, now);
    assert.equal(url.searchParams.get('timestamp'), String(now));
    assert.equal(url.searchParams.get('sign'), createHmac('sha256', secret).update(`${now}\n${secret}`).digest('base64'));
    assert.equal(url.searchParams.getAll('sign').length, 1);
    assert.equal(url.hash, '');
});

test('only official HTTPS robot endpoint is accepted', () => {
    for (const invalid of ['not-a-url', webhook.replace('https:', 'http:'),
        webhook.replace('oapi.dingtalk.com', 'oapi.dingtalk.com.example.com'),
        webhook.replace('/robot/send', '/other'), webhook.replace('https://', 'https://user:password@'),
        'https://oapi.dingtalk.com/robot/send']) {
        assert.throws(() => signedWebhook(invalid, secret, now));
    }
    assert.throws(() => signedWebhook(webhook, '', now), /加签密钥/);
});

for (const phase of ['post', 'failure', 'rollback']) {
    test(`${phase} notification contains only outcome fields and Beijing time`, () => {
        const data = container(phase);
        data.labels = { password: 'should-not-be-sent' };
        data.error = { message: 'access_token=should-not-be-sent' };
        const text = message(data, now);
        assert.match(text, /asset-management-backend-1/);
        assert.match(text, /test9 → test12/);
        assert.match(text, /2026\/10\/16/);
        assert.doesNotMatch(text, /should-not-be-sent/);
        if (phase !== 'post') assert.doesNotMatch(text, /WUD 更新完成/);
        if (phase === 'rollback') assert.match(text, /不能仅凭本通知认定回滚成功/);
    });
}

test('discovery, missing events and the upstream Test Trigger do not send messages', async () => {
    for (const data of [null, {}, { result: { tag: '4.5.6' }, updateAvailable: true }, container('pre')]) {
        const env = { ...environment(), container_json: JSON.stringify(data) };
        assert.equal(await notify(env, { fetchImpl: () => assert.fail('must not send') }), 'ignored');
    }
});

test('disabled notifications skip parsing and network access', async () => {
    assert.equal(await notify({ container_json: 'invalid' }, { fetchImpl: () => assert.fail('must not send') }), 'disabled');
});

test('plain text JSON is sent with timeout, no redirects and no @all', async () => {
    let request;
    const status = await notify(environment(), { now, fetchImpl: async (url, options) => {
        request = { url, options };
        return ok();
    } });
    assert.equal(status, 'sent');
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.redirect, 'error');
    assert.ok(request.options.signal instanceof AbortSignal);
    const payload = JSON.parse(request.options.body);
    assert.equal(payload.msgtype, 'text');
    assert.equal(payload.at.isAtAll, false);
    assert.match(payload.text.content, /WUD 更新完成/);
    assert.doesNotMatch(request.options.body, /example-token|example-secret/);
});

test('HTTP 200 with a nonzero DingTalk errcode is not treated as success', async () => {
    await assert.rejects(notify(environment(), { fetchImpl: async () => ({ ok: true, json: async () => ({ errcode: 310000 }) }) }), /310000/);
    await assert.rejects(notify(environment(), { fetchImpl: async () => ({ ok: true, json: async () => ({}) }) }), /被拒绝/);
});

test('HTTP errors and invalid response JSON are rejected', async () => {
    await assert.rejects(notify(environment(), { fetchImpl: async () => ({ ok: false, status: 403 }) }), /HTTP 403/);
    await assert.rejects(notify(environment(), { fetchImpl: async () => ({ ok: true, json: async () => { throw new Error('private response'); } }) }), /格式无效/);
});

test('notification failures never fail an update hook or leak credentials', async () => {
    const lines = [];
    const code = await main([], environment(), { log: (s) => lines.push(s), warn: (s) => lines.push(s),
        fetchImpl: async () => { throw new Error(`${webhook}&sign=private ${secret}`); } });
    assert.equal(code, 0);
    assert.match(lines.join('\n'), /通知失败不改变业务更新结果/);
    assert.doesNotMatch(lines.join('\n'), /example-token|example-secret|sign=|https:/);
});

test('malformed event data does not break the update hook', async () => {
    const lines = [];
    assert.equal(await main([], { ...environment(), container_json: 'invalid' }, { warn: (s) => lines.push(s) }), 0);
    assert.match(lines.join('\n'), /事件格式无效/);
});

test('request timeout is nonfatal to hooks but fails the explicit notification test', async () => {
    const options = { log: () => {}, warn: () => {}, fetchImpl: async () => { throw new DOMException('timed out', 'TimeoutError'); } };
    assert.equal(await main([], environment(), options), 0);
    assert.equal(await main(['--test'], environment(), options), 1);
});

test('explicit --test sends only a test message, without requiring a container event', async () => {
    let text;
    assert.equal(await main(['--test'], { WUD_DINGTALK_WEBHOOK: webhook, WUD_DINGTALK_SECRET: secret }, {
        now, log: () => {}, fetchImpl: async (_url, options) => { text = JSON.parse(options.body).text.content; return ok(); },
    }), 0);
    assert.match(text, /此次仅发送消息，不执行镜像更新/);
    assert.doesNotMatch(text, /4\.5\.6|更新完成/);
});

test('explicit --test fails visibly without credentials and performs no network request', async () => {
    assert.equal(await main(['--test'], {}, { warn: () => {}, fetchImpl: () => assert.fail('must not send') }), 1);
});
