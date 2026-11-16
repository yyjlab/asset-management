const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { spawn, spawnSync } = require('node:child_process');

const nginx = process.argv[2] || 'nginx';
const template = fs.readFileSync(path.resolve(__dirname, '../frontend/nginx.conf'), 'utf8');
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const close = server => new Promise(resolve => server.close(resolve));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const get = (port, pathname, headers = {}) => new Promise((resolve, reject) => {
  http.get({ host: '127.0.0.1', port, path: pathname, headers }, response => {
    let body = ''; response.on('data', chunk => body += chunk); response.on('end', () => resolve({ status: response.statusCode, body }));
  }).on('error', reject);
});

async function check(trustedCidr, expected) {
  const folder = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'asset-proxy-test-')));
  const upstream = http.createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(request.headers));
  });
  let child;
  try {
    await listen(upstream);
    const reservation = http.createServer(); await listen(reservation);
    const port = reservation.address().port; await close(reservation);
    fs.mkdirSync(path.join(folder, 'logs'));
    fs.mkdirSync(path.join(folder, 'temp'));
    const server = template.replaceAll('${ASSET_TRUSTED_PROXY_CIDR}', trustedCidr)
      .replace('listen 80;', `listen 127.0.0.1:${port};`)
      .replaceAll('http://backend:8080', `http://127.0.0.1:${upstream.address().port}`);
    fs.writeFileSync(path.join(folder, 'nginx.conf'), `daemon off; master_process off;\nerror_log logs/error.log;\nevents { worker_connections 32; }\nhttp { access_log off;\n${server}\n}\n`);
    const prefix = folder.replaceAll('\\', '/') + '/';
    const syntax = spawnSync(nginx, ['-t', '-p', prefix, '-c', 'nginx.conf'], { encoding: 'utf8', windowsHide: true });
    assert.ifError(syntax.error); assert.equal(syntax.status, 0, syntax.stderr);
    child = spawn(nginx, ['-p', prefix, '-c', 'nginx.conf'], { stdio: 'ignore', windowsHide: true });
    child.on('error', () => {});
    let ready = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      try { ready = (await get(port, '/healthz')).status === 200; if (ready) break; } catch {}
      await delay(50);
    }
    assert.ok(ready, 'isolated nginx must start');
    for (const pathname of ['/api/probe', '/api/v1/auth/dingtalk/callback']) {
      const headers = JSON.parse((await get(port, pathname, { 'X-Forwarded-Proto': 'https', Forwarded: 'proto=https', 'X-Forwarded-Ssl': 'on' })).body);
      assert.equal(headers['x-forwarded-proto'], expected);
      assert.equal(headers.forwarded, undefined); assert.equal(headers['x-forwarded-ssl'], undefined);
      for (const forwarded of ['http', 'https, http', 'invalid']) {
        const actual = JSON.parse((await get(port, pathname, { 'X-Forwarded-Proto': forwarded })).body);
        assert.equal(actual['x-forwarded-proto'], 'http', 'invalid or chained values must not elevate transport security');
      }
      assert.equal(JSON.parse((await get(port, pathname)).body)['x-forwarded-proto'], 'http');
    }
    console.log(`PASS nginx syntax and both API routes: trusted=${trustedCidr}, forwarded https=${expected}`);
  } finally {
    if (child && child.exitCode === null) {
      const stopped = new Promise(resolve => child.once('exit', resolve)); child.kill(); await stopped;
    }
    await close(upstream);
    assert.equal(path.dirname(path.resolve(folder)), fs.realpathSync.native(os.tmpdir()));
    fs.rmSync(folder, { recursive: true, force: true });
  }
}

check('127.0.0.1/32', 'https').then(() => check('192.0.2.1/32', 'http')).catch(error => { console.error(error); process.exitCode = 1; });
