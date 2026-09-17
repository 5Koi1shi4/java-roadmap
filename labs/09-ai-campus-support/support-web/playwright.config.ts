import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  reporter: [['line']],
  use: {
    baseURL: process.env.SUPPORT_E2E_BASE_URL ?? 'http://127.0.0.1:18000',
    screenshot: 'off',
    trace: 'off',
    video: 'off'
  },
  projects: [
    { name: 'desktop-chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'] } }
  ],
  webServer: {
    command: 'node e2e/model-stub.mjs',
    url: 'http://127.0.0.1:18089/health',
    reuseExistingServer: false,
    timeout: 30_000
  }
});
