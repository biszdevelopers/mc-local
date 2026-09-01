'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { once } = require('node:events');
const { createApp } = require('../server');

async function fixture(run) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'watcher-content-server-'));
  const deployRoot = path.join(temporaryRoot, 'deploy');
  const release = 'assemble_1.20.1_20260901010203';
  const hash = 'a'.repeat(64);
  fs.mkdirSync(path.join(deployRoot, release, 'mods'), { recursive: true });
  fs.writeFileSync(path.join(deployRoot, release, 'manifest.json'), JSON.stringify({ release }));
  fs.writeFileSync(path.join(deployRoot, release, 'mods', `${hash}.jar`), 'jar-content');
  fs.writeFileSync(path.join(deployRoot, 'latest.json'), JSON.stringify({
    latest: `/build/deploy/${release}/manifest.json`
  }));
  const configPath = path.join(temporaryRoot, 'config.json');
  fs.writeFileSync(configPath, JSON.stringify({ minecraftVersion: '1.20.1', latest: 'must-be-overridden' }));
  const server = createApp({ deployRoot, configPath }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const address = server.address();
    await run({ baseUrl: `http://127.0.0.1:${address.port}`, release, hash });
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

test('serves root configuration and immutable deployment files', async () => {
  await fixture(async ({ baseUrl, release, hash }) => {
    const root = await fetch(`${baseUrl}/`);
    assert.equal(root.status, 200);
    assert.equal(root.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await root.json(), {
      minecraftVersion: '1.20.1',
      latest: `/build/deploy/${release}/manifest.json`
    });

    const manifest = await fetch(`${baseUrl}/build/deploy/${release}/manifest.json`);
    assert.equal(manifest.status, 200);
    assert.match(manifest.headers.get('cache-control'), /immutable/);

    const content = await fetch(`${baseUrl}/${release}/mods/${hash}.jar`, {
      headers: { Range: 'bytes=0-2' }
    });
    assert.equal(content.status, 206);
    assert.equal(await content.text(), 'jar');
  });
});

test('rejects traversal and unknown content', async () => {
  await fixture(async ({ baseUrl, release }) => {
    assert.equal((await fetch(`${baseUrl}/${release}/mods/not-a-hash.jar`)).status, 404);
    assert.equal((await fetch(`${baseUrl}/${release}/%2e%2e/package.json`)).status, 404);
  });
});

test('returns 503 when no deployment has been published', async () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'watcher-empty-server-'));
  const server = createApp({ deployRoot: temporaryRoot, configPath: path.join(temporaryRoot, 'missing.json') })
    .listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const address = server.address();
    const response = await fetch(`http://127.0.0.1:${address.port}/`);
    assert.equal(response.status, 503);
    assert.equal((await response.json()).error, 'content_unavailable');
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
});
