#!/usr/bin/env node
// Wrapper to start electron with optional gateway token and optional local ws test server
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const gatewayFile1 = path.join(projectRoot, '.gateway_token');
const gatewayFile2 = path.join(projectRoot, 'gateway.token');
const startWsMarker = path.join(projectRoot, '.start-ws-test');
const wsTestScript = path.join(projectRoot, 'scripts', 'ws-gateway-test.js');

function loadGatewayToken() {
  try {
    if (fs.existsSync(gatewayFile1)) return fs.readFileSync(gatewayFile1, 'utf8').trim();
    if (fs.existsSync(gatewayFile2)) return fs.readFileSync(gatewayFile2, 'utf8').trim();
  } catch (e) {
    // ignore
  }
  return null;
}

function startWsTestServer() {
  if (!fs.existsSync(wsTestScript)) return null;
  // spawn node in background
  const proc = spawn(process.execPath, [wsTestScript], { stdio: 'inherit', detached: true });
  proc.unref();
  return proc;
}

(async () => {
  const token = loadGatewayToken();
  if (token) {
    process.env.BACKEND_GATEWAY_TOKEN = token;
    console.log('[start-electron] Loaded BACKEND_GATEWAY_TOKEN from file');
  }

  // If backend is not reachable and .start-ws-test exists, start the ws test server and
  // point BACKEND_URL to test server
  const defaultBackend = process.env.BACKEND_URL || 'http://localhost:8080';
  const testServerUrl = 'http://localhost:8090';

  const http = require('http');
  const checkBackend = () => new Promise((resolve) => {
    try {
      const req = http.request(defaultBackend + '/actuator/health', { method: 'GET', timeout: 1500 }, (res) => { resolve(res.statusCode < 500); });
      req.on('error', () => resolve(false));
      req.on('timeout', () => { req.destroy(); resolve(false); });
      req.end();
    } catch (e) { resolve(false); }
  });

  const backendOk = await checkBackend();
  if (!backendOk && fs.existsSync(startWsMarker)) {
    console.log('[start-electron] Backend not reachable, starting local WS test server');
    startWsTestServer();
    process.env.BACKEND_URL = testServerUrl;
  }

  // Finally run electron from project root to ensure correct node_modules are used
  if (process.env.SKIP_ELECTRON_LAUNCH) {
    console.log('[start-electron] SKIP_ELECTRON_LAUNCH is set; skipping actual electron launch (test mode)');
    return;
  }
  const npmCmd = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const child = spawn(npmCmd, ['run', 'electron:run-wrapper'], { cwd: projectRoot, stdio: 'inherit', env: process.env, shell: true });
  child.on('exit', (code) => process.exit(code));
})();
