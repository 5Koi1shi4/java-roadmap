import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const supportWeb = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lab = resolve(supportWeb, '..');

export default async function globalSetup() {
  const temp = mkdtempSync(join(tmpdir(), 'campus-support-e2e-'));
  const project = `campus-support-e2e-${process.pid}`;
  const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const privateKey = join(temp, 'jwt-private.pem');
  const publicKey = join(temp, 'jwt-public.pem');
  writeFileSync(privateKey, keys.privateKey.export({ type: 'pkcs8', format: 'pem' }));
  writeFileSync(publicKey, keys.publicKey.export({ type: 'spki', format: 'pem' }));
  const secret = () => randomBytes(24).toString('hex');
  const envFile = join(temp, 'compose.env');
  writeFileSync(envFile, [
    `MYSQL_ROOT_PASSWORD=${secret()}`,
    `IDENTITY_APP_PASSWORD=${secret()}`,
    `IDENTITY_MIGRATOR_PASSWORD=${secret()}`,
    `MARKET_APP_PASSWORD=${secret()}`,
    `MARKET_MIGRATOR_PASSWORD=${secret()}`,
    `PRODUCT_APP_PASSWORD=${secret()}`,
    `PRODUCT_MIGRATOR_PASSWORD=${secret()}`,
    'RABBITMQ_DEFAULT_USER=e2e_user',
    `RABBITMQ_DEFAULT_PASS=${secret()}`,
    'MINIO_ROOT_USER=e2e_minio',
    `MINIO_ROOT_PASSWORD=${secret()}`,
    `CAMPUS_MARKET_IDENTITY_VERIFICATION_SECRET=${secret()}`,
    `CAMPUS_MARKET_PAYMENT_SIGNING_SECRET=${secret()}`,
    `CAMPUS_MARKET_SUPPORT_CURSOR_SECRET=${secret()}`,
    'CAMPUS_MARKET_JWT_ISSUER=http://gateway.test',
    'CAMPUS_MARKET_JWT_AUDIENCE=campus-market-api',
    `JWT_PRIVATE_KEY_FILE=${privateKey.replaceAll('\\', '/')}`,
    `JWT_PUBLIC_KEY_FILE=${publicKey.replaceAll('\\', '/')}`
  ].join('\n'));

  process.env.SUPPORT_E2E_STATE_FILE = join(temp, 'state.json');
  writeFileSync(process.env.SUPPORT_E2E_STATE_FILE, JSON.stringify({ lab, envFile, project, temp }));
  try {
    if (process.platform === 'win32') {
      execFileSync('cmd.exe', ['/d', '/s', '/c', 'mvnw.cmd package -Dmaven.test.skip=true'], {
        cwd: lab, stdio: 'inherit'
      });
    } else {
      execFileSync('./mvnw', ['package', '-Dmaven.test.skip=true'], { cwd: lab, stdio: 'inherit' });
    }
    execFileSync('docker', ['compose', '--env-file', envFile, '-p', project, 'up', '-d', '--build'], {
      cwd: lab, stdio: 'inherit'
    });
    await waitFor('http://127.0.0.1:18081/actuator/health', 180_000);
    await waitFor('http://127.0.0.1:18082/actuator/health', 180_000);
    await waitFor('http://127.0.0.1:18084/actuator/health', 180_000);
    await waitFor('http://127.0.0.1:18080/actuator/health/readiness', 180_000);
    await waitFor('http://127.0.0.1:18000/', 30_000);
    await waitFor('http://127.0.0.1:8026/api/v1/messages?limit=1', 30_000);
    await waitForAnswer('http://127.0.0.1:18000/api/ai/support/answers', 30_000);
  } catch (error) {
    try {
      const resultDirectory = join(supportWeb, 'test-results');
      mkdirSync(resultDirectory, { recursive: true });
      const logs = execFileSync('docker', [
        'compose', '--env-file', envFile, '-p', project,
        'logs', '--no-color', '--timestamps'
      ], { cwd: lab, encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 });
      writeFileSync(join(resultDirectory, 'setup-compose.log'), logs, 'utf8');
    } catch { /* log capture is best effort */ }
    try {
      execFileSync('docker', ['compose', '--env-file', envFile, '-p', project, 'down', '-v', '--remove-orphans'], {
        cwd: lab, stdio: 'inherit'
      });
    } catch { /* retain original setup failure */ }
    rmSync(temp, { recursive: true, force: true });
    throw error;
  }
}

async function waitForAnswer(url: string, timeout: number) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=UTF-8' },
        body: JSON.stringify({ question: '退款规则是什么' })
      });
      if (response.ok) return;
    } catch { /* waiting for the real AI answer path */ }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
  }
  throw new Error(`真实问答链路未在限定时间内就绪: ${url}`);
}

async function waitFor(url: string, timeout: number) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch { /* waiting for Compose */ }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
  }
  throw new Error(`环境未在限定时间内就绪: ${url}`);
}
