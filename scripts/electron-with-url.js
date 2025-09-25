#!/usr/bin/env node
const { spawnSync, spawn } = require('child_process');

function log(...args) { console.log('[electron-with-url]', ...args); }

const argUrl = process.argv[2];
const envUrl = process.env.BACKEND_URL;
const url = argUrl || envUrl || 'http://localhost:8080';

log('Usando BACKEND_URL =', url);

// 1) build (TypeScript)
log('Rodando `npm run build`...');
const build = spawnSync('npm', ['run', 'build'], { stdio: 'inherit', shell: true });
if (build.status !== 0) {
  log('Build falhou, abortando.');
  process.exit(build.status || 1);
}

// 2) start electron with BACKEND_URL in env
const env = { ...process.env, BACKEND_URL: url };
log('Iniciando Electron...');
const electron = spawn('npx', ['electron', '.'], { stdio: 'inherit', shell: true, env });

electron.on('close', (code, signal) => {
  if (signal) {
    log('Electron finalizado por sinal', signal);
    process.exit(1);
  }
  log('Electron finalizado com código', code);
  process.exit(code);
});

electron.on('error', (err) => {
  log('Erro ao iniciar Electron:', err && err.message ? err.message : err);
  process.exit(1);
});
