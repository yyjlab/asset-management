'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { runInNewContext } = require('node:vm');
const { patchSource } = require('./patch-wud-swr-auth.cjs');

// TypeScript target ES2022 preserves this upstream expression in the image.
const original = 'const scope = effectiveChallenge.scope || (image?.name ? `repository:${image.name}:pull` : undefined);';
const fixture = `(function (image, registryUrl, effectiveChallenge) { ${original} return scope; })`;
const resolveScope = runInNewContext(patchSource(fixture));
const swr = 'https://swr.cn-south-1.myhuaweicloud.com';

for (const repo of ['recovery-uat/be-asset', 'recovery-uat/fe-asset']) {
    test(`SWR root challenge uses the actual repository: ${repo}`, () => {
        assert.equal(resolveScope({ name: repo }, swr, { scope: 'repository:/:pull' }), `repository:${repo}:pull`);
    });
}

test('other SWR regions and a trailing slash are supported', () => {
    assert.equal(resolveScope({ name: 'team/app' }, 'https://swr.cn-north-4.myhuaweicloud.com/', { scope: 'repository:/:pull' }), 'repository:team/app:pull');
});

test('a real repository challenge is preserved', () => {
    assert.equal(resolveScope({ name: 'team/app' }, swr, { scope: 'repository:team/other:pull,push' }), 'repository:team/other:pull,push');
});

test('missing challenge scope retains the upstream fallback', () => {
    assert.equal(resolveScope({ name: 'team/app' }, swr, {}), 'repository:team/app:pull');
});

test('missing image does not invent repository access', () => {
    assert.equal(resolveScope(undefined, swr, { scope: 'repository:/:pull' }), 'repository:/:pull');
    assert.equal(resolveScope(undefined, swr, {}), undefined);
});

for (const url of [
    'https://registry.example.com',
    'https://registry-1.docker.io',
    'https://swr.cn-south-1.myhuaweicloud.com.example.com',
    'http://swr.cn-south-1.myhuaweicloud.com',
]) {
    test(`nonmatching registries are unchanged: ${url}`, () => {
        assert.equal(resolveScope({ name: 'team/app' }, url, { scope: 'repository:/:pull' }), 'repository:/:pull');
    });
}

test('multiline compiled code and CRLF are supported', () => {
    const source = fixture.replace(original, 'const scope =\r\n  effectiveChallenge.scope ||\r\n  (image?.name ? `repository:${image.name}:pull` : undefined);');
    const resolve = runInNewContext(patchSource(source));
    assert.equal(resolve({ name: 'team/app' }, swr, { scope: 'repository:/:pull' }), 'repository:team/app:pull');
});

test('unknown, duplicate, already patched, or invalid source fails closed', () => {
    assert.throws(() => patchSource('const scope = "new upstream code";'), /found 0/);
    assert.throws(() => patchSource(`function a(){${original}} function b(){${original}}`), /found 2/);
    assert.throws(() => patchSource(patchSource(fixture)), /found 0/);
    assert.throws(() => patchSource(`${fixture}\nfunction {`), SyntaxError);
});
