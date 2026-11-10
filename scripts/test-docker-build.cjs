const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const shell = process.argv[2] || 'sh';
const backend = path.resolve(__dirname, '../backend');
const unix = value => value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase());
const script = path.join(backend, 'docker-maven-build.sh');

function simulate(mode, scenario) {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'asset-build-test-'));
  const log = path.join(folder, 'calls');
  try {
    fs.writeFileSync(path.join(folder, 'mvn'), `#!/bin/sh
printf '%s|%s\\n' "$MAVEN_REPOSITORY_URL" "$*" >> "$TEST_LOG"
case "$TEST_SCENARIO" in
 primary) exit 0 ;;
 fallback) test "$MAVEN_REPOSITORY_URL" = https://repo.maven.apache.org/maven2 ;;
 missing) case " $* " in *' -o '*) exit 1 ;; *) exit 0 ;; esac ;;
 fail) exit 7 ;;
esac
`, 'utf8');
    const result = spawnSync(shell, ['-c', 'PATH="$1:$PATH"; export PATH; chmod +x "$1/mvn"; exec sh "$2" "$3"',
      'build-test', unix(folder), unix(script), mode], {
      encoding: 'utf8', env: { ...process.env, TEST_SCENARIO: scenario, TEST_LOG: unix(log) }, timeout: 15000,
    });
    assert.ifError(result.error);
    return { ...result, calls: fs.existsSync(log) ? fs.readFileSync(log, 'utf8').trim().split('\n') : [] };
  } finally {
    assert.equal(path.dirname(path.resolve(folder)), path.resolve(os.tmpdir()));
    fs.rmSync(folder, { recursive: true, force: true });
  }
}

test('dependency warmup uses domestic repository before public Central', () => {
  const result = simulate('dependencies', 'primary');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.calls.length, 1);
  assert.match(result.calls[0], /^https:\/\/repo\.huaweicloud\.com\/repository\/maven\|/);
  assert.match(result.calls[0], /dependency:go-offline/);
});

test('unavailable domestic repositories fall back in explicit order', () => {
  const result = simulate('dependencies', 'fallback');
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.calls.map(call => call.split('|')[0]), [
    'https://repo.huaweicloud.com/repository/maven', 'https://maven.aliyun.com/repository/public',
    'https://repo.maven.apache.org/maven2',
  ]);
});

test('warm package build uses offline cache and skips test compilation', () => {
  const result = simulate('package', 'primary');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.calls.length, 1);
  assert.match(result.calls[0], /-o package -Dmaven\.test\.skip=true/);
  assert.ok(!result.calls[0].includes('clean'));
});

test('missing offline artifacts resolve online instead of breaking builds', () => {
  const result = simulate('package', 'missing');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.calls.length, 2);
  assert.match(result.calls[0], /-o package/);
  assert.ok(!result.calls[1].includes('-o package'));
});

test('all repository failures remain a failed build', () => {
  const result = simulate('dependencies', 'fail');
  assert.notEqual(result.status, 0);
  assert.equal(result.calls.length, 3);
});

test('invalid build mode fails without calling Maven', () => {
  const result = simulate('invalid', 'primary');
  assert.equal(result.status, 2);
  assert.equal(result.calls.length, 0);
});

test('Dockerfile isolates font cache from source and preserves CJK support', () => {
  const docker = fs.readFileSync(path.join(backend, 'Dockerfile'), 'utf8');
  assert.ok(docker.indexOf('AS runtime-base') < docker.indexOf('AS build'));
  assert.ok(docker.indexOf('COPY pom.xml') < docker.indexOf(' dependencies'));
  assert.ok(docker.indexOf(' dependencies') < docker.indexOf('COPY src/main'));
  assert.ok(!docker.includes('COPY src ./src'));
  assert.match(docker, /timeout 180 apk --timeout 20 --progress add --no-cache fontconfig font-noto-cjk/);
  assert.match(docker, /fc-match.*Noto Sans CJK SC/);
  assert.match(docker, /FROM runtime-base\nCOPY --from=build/);
  const run = docker.slice(docker.indexOf('RUN set -eu;') + 4, docker.indexOf('\n\nRUN addgroup')).replace(/\\\r?\n/g, '\n');
  const result = spawnSync(shell, ['-n'], { input: run, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
});
