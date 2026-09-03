import type {
  CraftingCpu,
  CraftingCpuDetail,
  CraftingPlan,
  CraftingRequest,
  Grid,
  GridMetrics,
  Health,
  Identity,
  Item,
  ItemPage,
  LinkCode,
} from './client'

const grids: Grid[] = [
  {
    id: '4a42648b-9d8f-4f26-bd0a-8b3f38409f91',
    alias: 'Orbital Fabrication',
    online: true,
    booting: false,
    controllerState: 'online',
    permissions: ['read', 'craft'],
  },
  {
    id: 'e9a717bd-a42d-491b-a938-6d32855d79c1',
    alias: 'Foundry Subnet',
    online: true,
    booting: false,
    controllerState: 'online',
    permissions: ['read'],
  },
  {
    id: '8160db63-c5da-48e6-92eb-83a38be721ae',
    alias: 'Deep Storage',
    online: false,
    booting: false,
    controllerState: 'no_controller',
    permissions: ['read'],
  },
]

const baseItems = [
  ['Fluix Crystal', 'ae2:fluix_crystal', 'Applied Energistics 2'],
  ['Calculation Processor', 'ae2:calculation_processor', 'Applied Energistics 2'],
  ['Engineering Processor', 'ae2:engineering_processor', 'Applied Energistics 2'],
  ['Logic Processor', 'ae2:logic_processor', 'Applied Energistics 2'],
  ['Certus Quartz Crystal', 'ae2:certus_quartz_crystal', 'Applied Energistics 2'],
  ['Silicon', 'ae2:silicon', 'Applied Energistics 2'],
  ['Steel Ingot', 'mekanism:ingot_steel', 'Mekanism'],
  ['Enriched Alloy', 'mekanism:alloy_infused', 'Mekanism'],
  ['Redstone', 'minecraft:redstone', 'Minecraft'],
  ['Glass', 'minecraft:glass', 'Minecraft'],
  ['Oak Log', 'minecraft:oak_log', 'Minecraft'],
  ['Iron Ingot', 'minecraft:iron_ingot', 'Minecraft'],
] as const

const items: Item[] = Array.from({ length: 640 }, (_, index) => {
  const base = baseItems[index % baseItems.length]
  const batch = Math.floor(index / baseItems.length)
  const resourceId = batch ? `${base[1]}_${batch + 1}` : base[1]
  return {
    keyToken: `kt_${index}_${resourceId.replaceAll(':', '_')}`,
    type: index % 29 === 0 ? 'fluid' : 'item',
    resourceId,
    displayName: batch ? `${base[0]} · Batch ${batch + 1}` : base[0],
    namespace: base[2],
    amount: index % 7 === 0 ? 0 : ((index * 7919) % 2_400_000) + 64,
    craftable: index % 3 !== 1,
  }
})

const plans = new Map<string, CraftingPlan>()
const requests = new Map<string, CraftingRequest>()

const pause = () => new Promise((resolve) => setTimeout(resolve, 90 + Math.random() * 140))

function jsonBody(options: { body?: unknown }) {
  return (options.body ?? {}) as Record<string, unknown>
}

function metrics(gridId: string): GridMetrics {
  const offline = gridId === grids[2].id
  return {
    revision: Date.now(),
    powered: !offline,
    storedPower: offline ? 0 : 768_400,
    maxStoredPower: 1_600_000,
    averagePowerUsage: offline ? 0 : 2_847.8,
    averagePowerInjection: offline ? 0 : 3_260.2,
    usedChannels: offline ? 0 : 117,
    deviceCount: offline ? 0 : 183,
    storedItemTypes: offline ? 0 : 428,
    craftableItemTypes: offline ? 0 : 271,
  }
}

function cpus(gridId: string): CraftingCpu[] {
  if (gridId === grids[2].id) return []
  return [
    {
      id: 'cpu-1',
      name: 'Quantum Assembly',
      busy: true,
      outputName: '64k ME Storage Component',
      progress: 0.68,
      elapsedMillis: 84_200,
      storageBytes: 67_108_864,
      coprocessors: 12,
    },
    {
      id: 'cpu-2',
      name: 'Molecular Line A',
      busy: true,
      outputName: 'Engineering Processor',
      progress: 0.31,
      elapsedMillis: 12_600,
      storageBytes: 16_777_216,
      coprocessors: 8,
    },
    {
      id: 'cpu-3',
      name: 'General Fabricator',
      busy: false,
      outputName: null,
      progress: null,
      elapsedMillis: null,
      storageBytes: 4_194_304,
      coprocessors: 4,
    },
  ]
}

export async function mockRequest<T>(
  path: string,
  options: Omit<RequestInit, 'body'> & { body?: unknown },
): Promise<T> {
  await pause()
  const url = new URL(path, 'http://mock.local')

  if (path === '/health') {
    return { status: 'ok', version: '0.1.0-dev', minecraftConnected: true } satisfies Health as T
  }
  if (path === '/identity') {
    return {
      subject: 'local-development',
      email: 'operator@example.test',
      linked: true,
      minecraftUuid: 'a3a36c7a-4e43-438c-bfb9-4aaf4b19f2cc',
      minecraftName: 'PixieOperator',
    } satisfies Identity as T
  }
  if (path === '/identity/link-code' && options.method === 'POST') {
    return {
      code: 'AE2X7K9P',
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    } satisfies LinkCode as T
  }
  if (path === '/grids') return { revision: Date.now(), grids } as T

  const gridMatch = url.pathname.match(/^\/grids\/([^/]+)\/(items|cpus|metrics|plans)(?:\/([^/]+))?$/)
  if (gridMatch) {
    const [, gridId, resource, nestedId] = gridMatch
    if (resource === 'metrics') return metrics(gridId) as T
    if (resource === 'cpus') {
      if (nestedId) {
        const cpu = cpus(gridId).find((candidate) => candidate.id === nestedId) ?? cpus(gridId)[0]
        return {
          cpu,
          suspended: false,
          entries: cpu.busy
            ? [
                {
                  displayName: cpu.outputName ?? 'Output',
                  resourceId: 'ae2:example',
                  stored: 12,
                  crafting: 48,
                  scheduled: 128,
                },
                {
                  displayName: 'Certus Quartz Crystal',
                  resourceId: 'ae2:certus_quartz_crystal',
                  stored: 64,
                  crafting: 0,
                  scheduled: 0,
                },
              ]
            : [],
        } satisfies CraftingCpuDetail as T
      }
      return { revision: Date.now(), cpus: cpus(gridId) } as T
    }
    if (resource === 'items') {
      const query = url.searchParams.get('query')?.toLowerCase()
      const craftable = url.searchParams.get('craftable')
      const filtered = items.filter(
        (item) =>
          (!query ||
            item.displayName.toLowerCase().includes(query) ||
            item.resourceId.toLowerCase().includes(query)) &&
          (craftable !== 'true' || item.craftable),
      )
      const limit = Math.min(500, Math.max(1, Number(url.searchParams.get('limit') ?? 200)))
      return {
        revision: Date.now(),
        nextCursor: filtered.length > limit ? 'mock-page-2' : null,
        items: filtered.slice(0, limit),
      } satisfies ItemPage as T
    }
    if (resource === 'plans' && options.method === 'POST') {
      const body = jsonBody(options)
      const item = items.find((candidate) => candidate.keyToken === body.keyToken) ?? items[0]
      const plan: CraftingPlan = {
        id: crypto.randomUUID(),
        gridId,
        state: 'calculating',
        requestedName: item.displayName,
        amount: Number(body.amount),
        bytes: Math.max(1024, Number(body.amount) * 12),
        expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
        simulation: false,
        entries: [],
        missing: [],
        error: null,
      }
      plans.set(plan.id, plan)
      setTimeout(() => plans.set(plan.id, {
        ...plan,
        state: 'ready',
        entries: [
          { displayName: 'Certus Quartz Crystal', stored: 64, craft: 0, missing: 0 },
          { displayName: 'Redstone', stored: 32, craft: 0, missing: 0 },
          { displayName: item.displayName, stored: 0, craft: Number(body.amount), missing: 0 },
        ],
      }), 750)
      return plan as T
    }
  }

  const planMatch = url.pathname.match(/^\/plans\/([^/]+)(\/submit)?$/)
  if (planMatch) {
    const plan = plans.get(planMatch[1])
    if (!plan) throw new Error('Mock plan not found')
    if (planMatch[2] && options.method === 'POST') {
      const request: CraftingRequest = {
        id: crypto.randomUUID(),
        planId: plan.id,
        state: 'accepted',
        createdAt: new Date().toISOString(),
        error: null,
      }
      requests.set(request.id, request)
      plans.set(plan.id, { ...plan, state: 'submitted' })
      return request as T
    }
    return plan as T
  }

  const requestMatch = url.pathname.match(/^\/requests\/([^/]+)$/)
  if (requestMatch && requests.has(requestMatch[1])) return requests.get(requestMatch[1]) as T

  throw new Error(`No development mock for ${options.method ?? 'GET'} ${path}`)
}
