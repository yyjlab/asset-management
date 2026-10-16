'use strict';

const fs = require('node:fs');
const { Script } = require('node:vm');

// Upstream 9.4.1: app/registries/DockerRegistryV2.ts#getBearerToken.
// SWR's /v2/ challenge refers to "/", not the repository being watched.
const originalScope = /const scope\s*=\s*effectiveChallenge\.scope\s*\|\|\s*\(image\?\.name\s*\?\s*`repository:\$\{image\.name\}:pull`\s*:\s*undefined\)\s*;/g;
const fixedScope = String.raw`const scope =
            image?.name &&
            /^https:\/\/swr\.[a-z0-9-]+\.myhuaweicloud\.com\/?$/i.test(registryUrl) &&
            effectiveChallenge.scope === 'repository:/:pull'
                ? ` + '`repository:${image.name}:pull`' + String.raw`
                : (effectiveChallenge.scope ||
                    (image?.name ? ` + '`repository:${image.name}:pull`' + String.raw` : undefined));`;

function patchSource(source) {
    const matches = [...source.matchAll(originalScope)];
    if (matches.length !== 1) {
        throw new Error(`Expected one WUD 9.4.1 scope expression, found ${matches.length}; refusing to patch`);
    }
    const patched = source.replace(originalScope, () => fixedScope);
    new Script(patched, { filename: 'DockerRegistryV2.js' });
    return patched;
}

if (require.main === module) {
    const root = '/home/node/app';
    const version = JSON.parse(fs.readFileSync(`${root}/package.json`, 'utf8')).version;
    if (version !== '9.4.1') {
        throw new Error(`Expected WUD 9.4.1, found ${version}; refusing to patch`);
    }
    const target = `${root}/dist/registries/DockerRegistryV2.js`;
    const patched = patchSource(fs.readFileSync(target, 'utf8'));
    fs.writeFileSync(target, patched);
    console.log('WUD 9.4.1: SWR repository scope fix applied');
}

module.exports = { patchSource };
