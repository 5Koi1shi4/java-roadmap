import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

export default function globalTeardown() {
  const stateFile = process.env.SUPPORT_E2E_STATE_FILE;
  if (!stateFile || !existsSync(stateFile)) return;
  const state = JSON.parse(readFileSync(stateFile, 'utf8')) as {
    lab: string; envFile: string; project: string; temp: string;
  };
  try {
    try {
      const resultDirectory = join(state.lab, 'support-web', 'test-results');
      mkdirSync(resultDirectory, { recursive: true });
      const logs = execFileSync('docker', [
        'compose', '--env-file', state.envFile, '-p', state.project,
        'logs', '--no-color', '--timestamps'
      ], { cwd: state.lab, encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 });
      writeFileSync(join(resultDirectory, 'compose.log'), logs, 'utf8');
    } finally {
      execFileSync('docker', ['compose', '--env-file', state.envFile, '-p', state.project, 'down', '-v', '--remove-orphans'], {
        cwd: state.lab, stdio: 'inherit'
      });
    }
  } finally {
    rmSync(state.temp, { recursive: true, force: true });
  }
}
