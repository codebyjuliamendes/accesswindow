const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
let child, base, temp;
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
before(async () => {
  temp = await fs.mkdtemp(path.join(os.tmpdir(), 'accesswindow-test-'));
  const port = 20000 + Math.floor(Math.random() * 40000); base = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['server.js'], { cwd: path.resolve(__dirname, '..'), env: { ...process.env, PORT: String(port), DATABASE_PATH: path.join(temp, 'test.sqlite') }, stdio: 'ignore' });
  for (let i = 0; i < 100; i++) { if (child.exitCode !== null) throw new Error('server exited early'); try { if ((await fetch(base + '/api/summary')).ok) return; } catch {} await wait(50); }
  throw new Error('server did not start');
});
after(async () => { if (child && child.exitCode === null) { child.kill(); await Promise.race([once(child, 'exit'), wait(2000)]); } if (temp) await fs.rm(temp, { recursive: true, force: true }); });
test('review endpoint includes active overdue and due-soon access; CSV quotes values', async () => {
  const response = await fetch(base + '/api/review-due'); assert.equal(response.status, 200);
  const rows = await response.json(); assert.ok(rows.some(x => x.daysLeft < 0)); assert.ok(rows.every(x => x.daysLeft <= 14));
  const csv = await fetch(base + '/api/review-due.csv'); assert.equal(csv.status, 200); assert.match(csv.headers.get('content-type'), /text\/csv/); assert.match(await csv.text(), /email,resource,role/);
});
