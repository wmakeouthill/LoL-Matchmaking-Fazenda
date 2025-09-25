const fs = require('fs');
const path = require('path');

const secretsPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'application-secrets.properties');
const outPath = path.join(__dirname, '..', '.env.docker');

function parseProperties(content) {
  const lines = content.split(/\r?\n/);
  const obj = {};
  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith('#') || line.startsWith('//')) continue;
    const idx = line.indexOf('=');
    if (idx === -1) continue;
    const key = line.substring(0, idx).trim();
    const val = line.substring(idx + 1).trim();
    obj[key] = val;
  }
  return obj;
}

if (!fs.existsSync(secretsPath)) {
  console.error('application-secrets.properties not found at', secretsPath);
  console.error('Crie esse arquivo com as credenciais localmente ou exporte as variáveis manualmente.');
  process.exit(1);
}

const content = fs.readFileSync(secretsPath, 'utf8');
const props = parseProperties(content);

const out = {};

// Prefer JDBC URL if present
if (props['spring.datasource.url']) {
  out['DATABASE_URL'] = props['spring.datasource.url'];
  // tentar extrair host/port/database de jdbc:mysql://host:port/db
  const m = props['spring.datasource.url'].match(/jdbc:mysql:\/\/([^:\/]+)(?::(\d+))?\/(\w+)/);
  if (m) {
    out['MYSQL_HOST'] = m[1];
    if (m[2]) out['MYSQL_PORT'] = m[2];
    out['MYSQL_DATABASE'] = m[3];
  }
}

// fallback to individual props
if (props['spring.datasource.username']) out['MYSQL_USER'] = props['spring.datasource.username'];
if (props['spring.datasource.password']) out['MYSQL_PASSWORD'] = props['spring.datasource.password'];
if (props['MYSQL_HOST']) out['MYSQL_HOST'] = props['MYSQL_HOST'];
if (props['MYSQL_PORT']) out['MYSQL_PORT'] = props['MYSQL_PORT'];
if (props['MYSQL_DATABASE']) out['MYSQL_DATABASE'] = props['MYSQL_DATABASE'];

// If DATABASE_URL not set but we have parts, build it
if (!out['DATABASE_URL'] && out['MYSQL_HOST'] && out['MYSQL_DATABASE']) {
  const host = out['MYSQL_HOST'];
  const port = out['MYSQL_PORT'] || '3306';
  out['DATABASE_URL'] = `jdbc:mysql://${host}:${port}/${out['MYSQL_DATABASE']}?useSSL=false&serverTimezone=UTC`;
}

// Write .env.docker with KEY=VALUE lines
const lines = [];
for (const k of Object.keys(out)) {
  lines.push(`${k}=${out[k]}`);
}

fs.writeFileSync(outPath, lines.join('\n'));
console.log('Gerado', outPath);
console.log('Conteúdo não será mostrado aqui (contém segredos).');
console.log('Use este arquivo no docker run: docker run --env-file .env.docker ...');

