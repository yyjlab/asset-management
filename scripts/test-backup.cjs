const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const shell = process.argv[2] || 'bash';
const script = path.resolve(__dirname, 'backup-mysql.sh');
const unix = value => value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase());

for (const mode of ['success', 'dump-failure', 'archive-failure', 'existing-guard']) {
  test(`backup ${mode}: preserve images, release only owned guard, and publish only complete backups`, () => {
    const folder = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'asset-backup-test-')));
    const data = path.join(folder, 'data');
    const images = path.join(data, 'asset-images');
    const backups = path.join(folder, 'backups');
    const guard = path.join(images, '.backup-in-progress');
    fs.mkdirSync(images, { recursive: true });
    fs.writeFileSync(path.join(images, 'snapshot.png'), 'database snapshot references this image');
    if (mode === 'existing-guard') fs.mkdirSync(guard);
    try {
      fs.writeFileSync(path.join(folder, 'docker'), `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$TEST_LOG"
case "$*" in
  'compose ps -q mysql') printf 'mysql\\n' ;;
  'compose ps -q backend') printf 'backend\\n' ;;
  'compose exec -T mysql '*)
    test -d "$TEST_DATA/asset-images/.backup-in-progress"
    if [[ "$TEST_MODE" == dump-failure ]]; then exit 7; fi
    printf 'INSERT INTO assets VALUES (1);\\n'
    ;;
  'compose exec -T backend '*)
    command="$7"
    if [[ "$command" == tar* && "$TEST_MODE" == archive-failure ]]; then exit 9; fi
    command="\${command//\\/app\\/data/$TEST_DATA}"
    bash -c "$command"
    ;;
  *) exit 88 ;;
esac
`, 'utf8');
      // The application image guard is tested here; flock's OS implementation is not under test.
      fs.writeFileSync(path.join(folder, 'flock'), '#!/bin/sh\nexit 0\n');
      if (process.platform === 'win32') {
        // Git Bash cannot apply Linux mode 700 to Windows ACLs; only bypass that OS-specific step.
        fs.writeFileSync(path.join(folder, 'install'), '#!/bin/sh\ntest "$1 $2 $3" = "-d -m 700" || exit 87\nmkdir -p "$4"\n');
      }
      const result = spawnSync(shell, ['-c', 'PATH="$1:$PATH"; export PATH; chmod +x "$1/docker" "$1/flock"; if test -f "$1/install"; then chmod +x "$1/install"; fi; exec bash "$2"',
        'backup-test', unix(folder), unix(script)], {
        env: { ...process.env, TEST_DATA: unix(data), TEST_MODE: mode, TEST_LOG: unix(path.join(folder, 'calls')),
          ASSET_BACKUP_DIR: unix(backups), ASSET_BACKUP_RETENTION: '12' },
        encoding: 'utf8', timeout: 20000,
      });
      assert.ifError(result.error);
      assert.ok(fs.existsSync(backups), result.stderr || result.stdout || `backup exited ${result.status}`);
      const files = fs.readdirSync(backups);
      const calls = fs.readFileSync(path.join(folder, 'calls'), 'utf8');
      assert.equal(calls.includes('compose exec -T mysql '), mode !== 'existing-guard');
      assert.equal(calls.includes('backend sh -c tar '), mode === 'success' || mode === 'archive-failure');
      assert.equal(fs.existsSync(guard), mode === 'existing-guard', result.stderr);
      assert.ok(fs.existsSync(path.join(images, 'snapshot.png')));
      assert.ok(files.every(name => !name.endsWith('.partial')), 'failed backups must not leave partial archives');
      if (mode !== 'success') {
        assert.notEqual(result.status, 0, result.stdout);
        assert.ok(files.every(name => name === '.backup.lock'), 'failed snapshots must not be published');
        return;
      }
      assert.equal(result.status, 0, result.stderr);
      const sql = files.find(name => name.endsWith('.sql.gz'));
      const archive = files.find(name => name.endsWith('.images.tar.gz'));
      assert.ok(sql && archive && files.includes(sql + '.sha256'));
      const listed = spawnSync(shell, ['-c', 'cd "$1" && sha256sum -c "$2" && tar -tzf "$3"',
        'verify-backup', unix(backups), sql + '.sha256', archive], { encoding: 'utf8' });
      assert.equal(listed.status, 0, listed.stderr);
      assert.match(listed.stdout, /asset-images\/snapshot\.png/);
      assert.ok(!listed.stdout.includes('.backup-in-progress'), 'restoring a backup must not restore the guard');
    } finally {
      assert.equal(path.dirname(path.resolve(folder)), fs.realpathSync.native(os.tmpdir()));
      fs.rmSync(folder, { recursive: true, force: true });
    }
  });
}
