'use strict';

const express = require('express');
const fs = require('node:fs');
const path = require('node:path');

const RELEASE_PATTERN = /^assemble_1\.20\.1_\d{14}$/;
const CONTENT_FILE_PATTERN = /^[a-f0-9]{64}(?:\.[A-Za-z0-9_-]+)?$/;
const LATEST_PATTERN = /^\/build\/deploy\/(assemble_1\.20\.1_\d{14})\/manifest\.json$/;

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function isRegularFile(filePath) {
  try {
    return fs.statSync(filePath).isFile();
  } catch (_error) {
    return false;
  }
}

function createApp(options = {}) {
  const deployRoot = path.resolve(options.deployRoot || process.env.DEPLOY_ROOT || path.join(__dirname, '..', 'build', 'deploy'));
  const configPath = path.resolve(options.configPath || path.join(__dirname, 'config.json'));
  const app = express();
  app.disable('x-powered-by');

  app.get('/healthz', (_request, response) => {
    response.set('Cache-Control', 'no-store').json({ status: 'ok' });
  });

  app.get('/', (_request, response) => {
    try {
      const baseConfig = fs.existsSync(configPath) ? readJson(configPath) : {};
      const latestConfig = readJson(path.join(deployRoot, 'latest.json'));
      if (typeof latestConfig.latest !== 'string' || !LATEST_PATTERN.test(latestConfig.latest)) {
        throw new Error('The generated latest pointer is invalid.');
      }
      const release = latestConfig.latest.match(LATEST_PATTERN)[1];
      if (!isRegularFile(path.join(deployRoot, release, 'manifest.json'))) {
        throw new Error('The latest deployment manifest does not exist.');
      }
      response.set('Cache-Control', 'no-store').json({ ...baseConfig, latest: latestConfig.latest });
    } catch (error) {
      response.status(503).set('Cache-Control', 'no-store').json({
        error: 'content_unavailable',
        message: error instanceof Error ? error.message : 'Content is unavailable.'
      });
    }
  });

  app.get('/build/deploy/:release/manifest.json', (request, response, next) => {
    const { release } = request.params;
    if (!RELEASE_PATTERN.test(release)) {
      return next();
    }
    const manifestPath = path.join(deployRoot, release, 'manifest.json');
    if (!isRegularFile(manifestPath)) {
      return next();
    }
    response.set('Cache-Control', 'public, max-age=31536000, immutable');
    return response.sendFile(manifestPath);
  });

  app.use((request, response, next) => {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return next();
    }
    let decodedPath;
    try {
      decodedPath = decodeURIComponent(request.path);
    } catch (_error) {
      return next();
    }
    const segments = decodedPath.split('/').filter(Boolean);
    if (segments.length < 3 || !RELEASE_PATTERN.test(segments[0])) {
      return next();
    }
    const fileName = segments[segments.length - 1];
    if (!CONTENT_FILE_PATTERN.test(fileName) || segments.slice(1, -1).some(segment => segment === '.' || segment === '..')) {
      return next();
    }
    const releaseRoot = path.resolve(deployRoot, segments[0]);
    const contentPath = path.resolve(releaseRoot, ...segments.slice(1));
    if (!contentPath.startsWith(releaseRoot + path.sep) || !isRegularFile(contentPath)) {
      return next();
    }
    response.set('Cache-Control', 'public, max-age=31536000, immutable');
    return response.sendFile(contentPath);
  });

  app.use((_request, response) => {
    response.status(404).json({ error: 'not_found' });
  });

  app.use((error, _request, response, _next) => {
    response.status(500).json({
      error: 'server_error',
      message: error instanceof Error ? error.message : 'Unexpected server error.'
    });
  });

  return app;
}

if (require.main === module) {
  const host = process.env.HOST || '127.0.0.1';
  const port = Number.parseInt(process.env.PORT || '4748', 10);
  createApp().listen(port, host, () => {
    process.stdout.write(`Minecraft content server listening on http://${host}:${port}\n`);
  });
}

module.exports = { createApp };
