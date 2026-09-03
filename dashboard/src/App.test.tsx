import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import App from './App'

const gatewayApi = vi.hoisted(() => ({
  health: vi.fn(),
  identity: vi.fn(),
  createLinkCode: vi.fn(),
  grids: vi.fn(),
  items: vi.fn(),
  cpus: vi.fn(),
  metrics: vi.fn(),
  createPlan: vi.fn(),
  plan: vi.fn(),
  submitPlan: vi.fn(),
  craftingRequest: vi.fn(),
}))

vi.mock('./api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('./api/client')>()
  return { ...original, api: gatewayApi }
})

function renderApp() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  gatewayApi.health.mockResolvedValue({ status: 'ok', version: '0.1.0', minecraftConnected: true })
  gatewayApi.identity.mockResolvedValue({
    subject: 'test-user',
    email: 'operator@example.test',
    linked: true,
    minecraftName: 'TestOperator',
  })
  gatewayApi.grids.mockResolvedValue({
    revision: 1,
    grids: [{
      id: 'grid-1',
      alias: 'Primary Network',
      online: true,
      booting: false,
      controllerState: 'online',
      permissions: ['read', 'craft'],
    }],
  })
  gatewayApi.cpus.mockResolvedValue({ revision: 3, cpus: [] })
  gatewayApi.items.mockResolvedValue({
    revision: 4,
    nextCursor: null,
    items: [{
      keyToken: 'tok-1',
      type: 'item',
      resourceId: 'ae2:fluix_crystal',
      displayName: 'Fluix Crystal',
      namespace: 'ae2',
      amount: 128,
      craftable: true,
    }],
  })
})

it('renders the terminal inventory grid', async () => {
  renderApp()

  expect(await screen.findByText('AE2 Gateway')).toBeInTheDocument()
  expect(await screen.findByText(/Primary Network online/)).toBeInTheDocument()
  expect(await screen.findByText('Fluix Crystal')).toBeInTheDocument()
  expect(screen.getByText('View: All')).toBeInTheDocument()
  expect(screen.getByText('Sort: # Items')).toBeInTheDocument()
  expect(await screen.findByText(/linked as TestOperator/)).toBeInTheDocument()
})
