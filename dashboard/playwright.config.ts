import { defineConfig, devices } from '@playwright/test'
import { existsSync } from 'node:fs'

const localEdge = '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    ...(existsSync(localEdge) ? { launchOptions: { executablePath: localEdge } } : {}),
  },
  webServer: {
    command: 'pnpm dev:mock --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
