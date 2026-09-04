import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import {
  api,
  type CraftingCpu,
  type CraftingPlan,
  type Grid,
  type Item,
  readableError,
} from './api/client'
import './App.css'

const numberFmt = new Intl.NumberFormat('en-US')
const formatNumber = (value?: number | null) =>
  value === undefined || value === null ? '—' : numberFmt.format(value)

function formatBytes(value?: number | null) {
  if (value === null || value === undefined) return '—'
  const units = ['B', 'KiB', 'MiB', 'GiB']
  let amount = value
  let unit = 0
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024
    unit += 1
  }
  return `${amount.toFixed(amount >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`
}

function formatClock(ms?: number) {
  if (!ms || !Number.isFinite(ms)) return '—'
  return new Date(ms).toLocaleTimeString()
}

type ViewMode = 'all' | 'stored' | 'craftable'
type SortMode = 'amount' | 'name' | 'mod'
type TypeMode = 'all' | 'item' | 'fluid'

function cycleView(mode: ViewMode, reverse = false): ViewMode {
  if (reverse) return mode === 'all' ? 'craftable' : mode === 'craftable' ? 'stored' : 'all'
  return mode === 'all' ? 'stored' : mode === 'stored' ? 'craftable' : 'all'
}

function cycleSort(mode: SortMode, reverse = false): SortMode {
  if (reverse) return mode === 'amount' ? 'mod' : mode === 'mod' ? 'name' : 'amount'
  return mode === 'amount' ? 'name' : mode === 'name' ? 'mod' : 'amount'
}

function cycleType(mode: TypeMode, reverse = false): TypeMode {
  if (reverse) return mode === 'all' ? 'fluid' : mode === 'fluid' ? 'item' : 'all'
  return mode === 'all' ? 'item' : mode === 'item' ? 'fluid' : 'all'
}

function onCycleClick(handler: (reverse: boolean) => void) {
  return {
    onClick: (event: { button?: number; preventDefault(): void }) => {
      if ((event.button ?? 0) === 0) handler(false)
    },
    onContextMenu: (event: { preventDefault(): void }) => {
      event.preventDefault()
      handler(true)
    },
  }
}

function viewLabel(mode: ViewMode) {
  return mode === 'all' ? 'View: All' : mode === 'stored' ? 'View: Stored' : 'View: Craftable'
}

function sortLabel(mode: SortMode) {
  return mode === 'amount' ? 'Sort: # Items' : mode === 'name' ? 'Sort: Name' : 'Sort: Mod'
}

function typeLabel(mode: TypeMode) {
  return mode === 'all' ? 'Type: All' : mode === 'item' ? 'Type: Items' : 'Type: Fluids'
}

function CraftPanel({
  item,
  grid,
  open,
  onOpenChange,
}: {
  item?: Item
  grid?: Grid
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const defaultAmount = item?.type === 'fluid' ? 0 : 1
  const [amount, setAmount] = useState(defaultAmount)
  const [planId, setPlanId] = useState<string>()
  const [requestState, setRequestState] = useState<string>()
  const [stockBefore, setStockBefore] = useState<number>()
  const [stockAfter, setStockAfter] = useState<number>()

  useEffect(() => {
    if (open) {
      setAmount(item?.type === 'fluid' ? 0 : 1)
      setPlanId(undefined)
      setRequestState(undefined)
      setStockBefore(undefined)
      setStockAfter(undefined)
    }
  }, [open, item?.keyToken, item?.type])

  const reset = () => {
    setAmount(item?.type === 'fluid' ? 0 : 1)
    setPlanId(undefined)
    setRequestState(undefined)
    setStockBefore(undefined)
    setStockAfter(undefined)
  }

  const createPlan = useMutation({
    mutationFn: () => api.createPlan(grid!.id, item!.keyToken, amount),
    onSuccess: (plan) => setPlanId(plan.id),
  })
  const plan = useQuery({
    queryKey: ['plan', planId],
    queryFn: () => api.plan(planId!),
    enabled: Boolean(planId),
    initialData: createPlan.data as CraftingPlan | undefined,
    refetchInterval: (query) => (query.state.data?.state === 'calculating' ? 500 : false),
  })
  const submit = useMutation({
    mutationFn: async () => {
      setStockBefore(item?.amount)
      const request = await api.submitPlan(planId!, crypto.randomUUID())
      // Give AE2 a moment, then re-read the item from a fresh inventory page.
      await new Promise((resolve) => window.setTimeout(resolve, 400))
      try {
        const page = await api.items(grid!.id, {
          limit: 20,
          query: item?.displayName ?? item?.resourceId,
        })
        const match = page.items.find((candidate) => candidate.keyToken === item?.keyToken)
          ?? page.items.find((candidate) => candidate.resourceId === item?.resourceId)
        if (match) setStockAfter(match.amount)
      } catch {
        // Stock refresh is best-effort; submit already succeeded.
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['items', grid!.id] }),
        queryClient.invalidateQueries({ queryKey: ['cpus', grid!.id] }),
      ])
      return request
    },
    onSuccess: (request) => setRequestState(request.state),
  })

  const canCraft = Boolean(grid?.permissions.includes('craft') && item?.craftable)
  const current = plan.data
  const ready = current?.state === 'ready'
  const unit = item?.type === 'fluid' ? ' mB' : ''
  const craftTotal = current?.entries?.find((entry) => entry.displayName === current.requestedName)?.craft

  return (
    <Dialog.Root open={open} onOpenChange={(next) => { if (!next) reset(); onOpenChange(next) }}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="craft-modal">
          <div className="craft-modal-top">
            <Dialog.Title>Craft {item?.displayName ?? 'item'}</Dialog.Title>
            <Dialog.Close className="btn">Close</Dialog.Close>
          </div>

          {!planId ? (
            <div className="craft-body">
              <div className="stack">
                <div><b>{item?.displayName}</b></div>
                <div className="mono muted">{item?.resourceId}</div>
                <div className="muted">Stored now: {formatNumber(item?.amount)}{unit}</div>
              </div>
              <label className="field">
                Amount{item?.type === 'fluid' ? ' (mB)' : ''}
                <input
                  type="number"
                  min={0}
                  max={1_000_000_000}
                  value={amount}
                  autoFocus
                  onChange={(event) => setAmount(Math.max(0, Number(event.target.value) || 0))}
                />
              </label>
              <div className="row gap wrap">
                {[10, 100, 1000].map((delta) => (
                  <button
                    key={delta}
                    type="button"
                    className="btn"
                    title={`Left +${delta} · Right -${delta}`}
                    onClick={() => setAmount((value) => Math.min(1_000_000_000, value + delta))}
                    onContextMenu={(event) => {
                      event.preventDefault()
                      setAmount((value) => Math.max(0, value - delta))
                    }}
                  >
                    ±{delta}
                  </button>
                ))}
                <button type="button" className="btn" onClick={() => setAmount(item?.type === 'fluid' ? 0 : 1)}>Reset</button>
              </div>
              {!canCraft && <p className="err">No craft permission for this item/network.</p>}
              {createPlan.error && <p className="err">{readableError(createPlan.error)}</p>}
              <button
                className="btn primary"
                disabled={!canCraft || createPlan.isPending || !Number.isSafeInteger(amount) || amount < 1}
                onClick={() => createPlan.mutate()}
              >
                {createPlan.isPending ? 'Calculating…' : 'Calculate plan'}
              </button>
            </div>
          ) : requestState ? (
            <div className="craft-body">
              <p><b>Submitted ({requestState})</b></p>
              <p>
                Requested {formatNumber(current?.amount)}{unit}
                {craftTotal != null && craftTotal !== current?.amount
                  ? ` · AE2 batched to craft ${formatNumber(craftTotal)}${unit}`
                  : ''}
              </p>
              {stockBefore != null && stockAfter != null ? (
                <p>
                  Network stock: {formatNumber(stockBefore)}{unit} → {formatNumber(stockAfter)}{unit}
                  {stockAfter !== stockBefore
                    ? ` (${stockAfter > stockBefore ? '+' : ''}${formatNumber(stockAfter - stockBefore)}${unit})`
                    : ' (unchanged yet — may still be running on a CPU)'}
                </p>
              ) : (
                <p className="muted">Check CPUs / inventory for progress.</p>
              )}
              <Dialog.Close className="btn">Done</Dialog.Close>
            </div>
          ) : (
            <div className="craft-body">
              {current?.state === 'calculating' ? (
                <p className="muted">Calculating…</p>
              ) : (
                <>
                  <div className="plan-header">
                    <b>
                      {current?.simulation ? '[Simulation] ' : ''}
                      Crafting Plan — {formatBytes(current?.bytes)}
                    </b>
                    <span className="muted">
                      {current?.requestedName} × {formatNumber(current?.amount)}{unit}
                      {craftTotal != null && craftTotal !== current?.amount
                        ? ` (crafts ${formatNumber(craftTotal)}${unit} in batches)`
                        : ''}
                    </span>
                  </div>
                  <div className="plan-grid">
                    {(current?.entries ?? []).map((entry) => (
                      <div
                        key={`${entry.displayName}-${entry.missing}-${entry.craft}-${entry.stored}`}
                        className={`plan-cell ${entry.missing > 0 ? 'missing' : ''}`}
                      >
                        <strong>{entry.displayName}</strong>
                        {entry.missing > 0 && <span>Missing: {formatNumber(entry.missing)}</span>}
                        {entry.craft > 0 && <span>To craft: {formatNumber(entry.craft)}</span>}
                        {entry.stored > 0 && <span>Available: {formatNumber(entry.stored)}</span>}
                        {entry.missing === 0 && entry.craft === 0 && entry.stored === 0 && (
                          <span className="muted">—</span>
                        )}
                      </div>
                    ))}
                    {!current?.entries?.length && !current?.missing?.length && (
                      <p className="muted">No plan entries.</p>
                    )}
                  </div>
                  {current?.state === 'missing' && (
                    <p className="err">Partial plan — missing ingredients. Cannot start.</p>
                  )}
                </>
              )}
              {(plan.error || submit.error || current?.error) && (
                <p className="err">{current?.error ?? readableError(plan.error ?? submit.error)}</p>
              )}
              <div className="row gap">
                <button
                  className="btn primary"
                  disabled={!ready || submit.isPending}
                  onClick={() => submit.mutate()}
                >
                  {submit.isPending ? 'Starting…' : 'Start'}
                </button>
                <Dialog.Close className="btn">Cancel</Dialog.Close>
              </div>
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function CpuStatus({
  gridId,
  cpu,
  open,
  onOpenChange,
}: {
  gridId?: string
  cpu?: CraftingCpu
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const detail = useQuery({
    queryKey: ['cpu', gridId, cpu?.id],
    queryFn: () => api.cpu(gridId!, cpu!.id),
    enabled: Boolean(open && gridId && cpu?.id),
    refetchInterval: open ? 2_000 : false,
  })
  const current = detail.data?.cpu ?? cpu

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="craft-modal wide">
          <div className="craft-modal-top">
            <Dialog.Title>{current?.name || 'CPU'} status</Dialog.Title>
            <Dialog.Close className="btn">Close</Dialog.Close>
          </div>
          <div className="craft-body">
            <div className="stack">
              <div>
                {current?.busy
                  ? `Crafting: ${current.outputName ?? 'unknown'}`
                  : 'Idle'}
                {detail.data?.suspended ? ' (suspended)' : ''}
              </div>
              <div className="muted">
                {formatBytes(current?.storageBytes)} · {current?.coprocessors ?? 0} co-proc
                {current?.progress != null ? ` · ${Math.round(current.progress * 100)}%` : ''}
                {current?.elapsedMillis != null ? ` · ${Math.round(current.elapsedMillis / 1000)}s` : ''}
              </div>
            </div>
            {detail.isLoading ? (
              <p className="muted">Loading job…</p>
            ) : detail.error ? (
              <p className="err">{readableError(detail.error)}</p>
            ) : !current?.busy ? (
              <p className="muted">No active job on this CPU.</p>
            ) : (
              <div className="plan-grid">
                {(detail.data?.entries ?? []).map((entry) => (
                  <div
                    key={`${entry.resourceId}-${entry.crafting}-${entry.scheduled}-${entry.stored}`}
                    className={`plan-cell ${entry.crafting > 0 ? 'active' : entry.scheduled > 0 ? 'pending' : ''}`}
                  >
                    <strong>{entry.displayName}</strong>
                    <span className="mono muted">{entry.resourceId}</span>
                    {entry.crafting > 0 && <span>Crafting: {formatNumber(entry.crafting)}</span>}
                    {entry.scheduled > 0 && <span>Scheduled: {formatNumber(entry.scheduled)}</span>}
                    {entry.stored > 0 && <span>Stored: {formatNumber(entry.stored)}</span>}
                  </div>
                ))}
                {!detail.data?.entries.length && (
                  <p className="muted">Job is running but no item breakdown was reported.</p>
                )}
              </div>
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function Terminal({ grid }: { grid?: Grid }) {
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [view, setView] = useState<ViewMode>('all')
  const [sort, setSort] = useState<SortMode>('amount')
  const [sortDesc, setSortDesc] = useState(true)
  const [type, setType] = useState<TypeMode>('all')
  const [selected, setSelected] = useState<Item>()
  const [craftOpen, setCraftOpen] = useState(false)
  const [selectedCpu, setSelectedCpu] = useState<CraftingCpu>()
  const [cpuOpen, setCpuOpen] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedSearch(search.trim()), 200)
    return () => window.clearTimeout(timer)
  }, [search])

  const inventory = useQuery({
    queryKey: ['items', grid?.id, debouncedSearch, view],
    queryFn: () => api.items(grid!.id, {
      limit: 2000,
      query: debouncedSearch || undefined,
      craftable: view === 'craftable' ? true : undefined,
    }),
    enabled: Boolean(grid?.id && grid.online),
    refetchInterval: 10_000,
  })
  const cpus = useQuery({
    queryKey: ['cpus', grid?.id],
    queryFn: () => api.cpus(grid!.id),
    enabled: Boolean(grid?.id && grid.online),
    refetchInterval: 4_000,
  })

  const items = useMemo(() => {
    const filtered = (inventory.data?.items ?? []).filter((item) => {
      if (view === 'stored' && item.amount <= 0) return false
      if (type !== 'all' && item.type !== type) return false
      return true
    })

    filtered.sort((a, b) => {
      let cmp = 0
      if (sort === 'amount') cmp = a.amount - b.amount
      else if (sort === 'name') cmp = a.displayName.localeCompare(b.displayName)
      else cmp = (a.namespace ?? a.resourceId).localeCompare(b.namespace ?? b.resourceId)
      return sortDesc ? -cmp : cmp
    })
    return filtered
  }, [inventory.data?.items, view, type, sort, sortDesc])

  return (
    <div className="terminal">
      <aside className="filters">
        <button
          type="button"
          className="btn cycle"
          title="Left next · Right previous"
          {...onCycleClick((reverse) => setView((value) => cycleView(value, reverse)))}
        >{viewLabel(view)}</button>
        <button
          type="button"
          className="btn cycle"
          title="Left next · Right previous"
          {...onCycleClick((reverse) => setSort((value) => cycleSort(value, reverse)))}
        >{sortLabel(sort)}</button>
        <button
          type="button"
          className="btn cycle"
          title="Left/Right toggle"
          onClick={() => setSortDesc((value) => !value)}
          onContextMenu={(event) => {
            event.preventDefault()
            setSortDesc((value) => !value)
          }}
        >
          {sortDesc ? 'Order: Desc' : 'Order: Asc'}
        </button>
        <button
          type="button"
          className="btn cycle"
          title="Left next · Right previous"
          {...onCycleClick((reverse) => setType((value) => cycleType(value, reverse)))}
        >{typeLabel(type)}</button>
        <div className="cpu-box">
          <div className="cpu-title">CPUs</div>
          {(cpus.data?.cpus ?? []).map((cpu) => (
            <button
              type="button"
              className={`cpu ${cpu.busy ? 'busy' : ''}`}
              key={cpu.id}
              onClick={() => { setSelectedCpu(cpu); setCpuOpen(true) }}
            >
              <div><b>{cpu.name || 'CPU'}</b></div>
              <div className="muted">{formatBytes(cpu.storageBytes)} · {cpu.coprocessors} co-proc</div>
              <div>{cpu.busy ? (cpu.outputName ?? 'Busy') : 'Idle'}</div>
              {cpu.progress != null && <div className="mono">{Math.round(cpu.progress * 100)}%</div>}
            </button>
          ))}
          {!cpus.isLoading && !(cpus.data?.cpus.length) && <div className="muted">No CPUs</div>}
        </div>
      </aside>

      <section className="terminal-main">
        <div className="terminal-toolbar">
          <input
            className="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search name or id…"
          />
          <span className="muted">
            {formatNumber(items.length)} shown
            {inventory.data?.nextCursor ? ' (more available — refine search)' : ''}
            {' · '}rev {inventory.data?.revision ?? '—'}
          </span>
          <button type="button" className="btn" onClick={() => void inventory.refetch()} disabled={!grid?.online}>
            Refresh items
          </button>
        </div>

        {!grid?.online ? (
          <div className="banner">Network offline — inventory paused.</div>
        ) : inventory.isLoading ? (
          <div className="banner">Loading inventory…</div>
        ) : inventory.error ? (
          <div className="banner err">{readableError(inventory.error)}</div>
        ) : (
          <div className="item-grid">
            {items.map((item) => (
              <button
                type="button"
                className="item-tile"
                key={item.keyToken}
                onClick={() => {
                  if (!item.craftable || !grid.permissions.includes('craft')) return
                  setSelected(item)
                  setCraftOpen(true)
                }}
                disabled={!item.craftable || !grid.permissions.includes('craft')}
                title={item.craftable ? 'Craft' : 'Not craftable'}
              >
                <strong>{item.displayName}</strong>
                <span className="mono muted">{item.resourceId}</span>
                <span>Stored: {formatNumber(item.amount)}{item.type === 'fluid' ? ' mB' : ''}</span>
                <span className="tile-flags">
                  {item.type === 'fluid' && <span className="type-tag">Fluid</span>}
                  {item.craftable && <span className="craft-tag">Craftable</span>}
                </span>
              </button>
            ))}
            {!items.length && (
              <div className="banner">
                No items match filters.
                {type !== 'all' ? ' Try Type: All — some GT materials are fluids.' : ''}
              </div>
            )}
          </div>
        )}
      </section>

      <CraftPanel item={selected} grid={grid} open={craftOpen} onOpenChange={setCraftOpen} />
      <CpuStatus gridId={grid?.id} cpu={selectedCpu} open={cpuOpen} onOpenChange={setCpuOpen} />
    </div>
  )
}

function App() {
  const health = useQuery({ queryKey: ['health'], queryFn: api.health, refetchInterval: 15_000 })
  const grids = useQuery({ queryKey: ['grids'], queryFn: api.grids, refetchInterval: 10_000 })
  const identity = useQuery({ queryKey: ['identity'], queryFn: api.identity, staleTime: 60_000 })
  const linkCode = useMutation({ mutationFn: api.createLinkCode })
  const grid = grids.data?.grids[0]

  useEffect(() => {
    document.documentElement.dataset.theme = 'light'
  }, [])

  const statusBits = [
    `gateway ${health.data?.status ?? '…'}`,
    health.data?.minecraftConnected ? 'mc connected' : 'mc down',
    grid ? `${grid.alias} ${grid.online ? 'online' : 'offline'}` : 'no grid',
    identity.data?.linked ? `linked as ${identity.data.minecraftName}` : 'not linked',
  ]

  return (
    <div className="app">
      <header className="bar">
        <b className="brand">AE2 Gateway</b>
        <span className="status">{statusBits.join(' · ')}</span>
        <span className="muted">grids {formatClock(grids.dataUpdatedAt)}</span>
        <button type="button" className="btn" onClick={() => void Promise.all([health.refetch(), grids.refetch()])}>
          Refresh
        </button>
        {import.meta.env.DEV && import.meta.env.MODE === 'mock' && <span className="badge">MOCK</span>}
      </header>

      {!identity.data?.linked && (
        <div className="link-strip">
          {linkCode.data ? (
            <span>Run in-game: <code>/ae2gateway link {linkCode.data.code}</code></span>
          ) : (
            <button type="button" className="btn" onClick={() => linkCode.mutate()} disabled={linkCode.isPending}>
              {linkCode.isPending ? 'Making code…' : 'Generate link code'}
            </button>
          )}
          {identity.error && <span className="err">{readableError(identity.error)}</span>}
        </div>
      )}

      {!grids.isLoading && !grid && (
        <div className="banner">No enrolled grid. Look at an AE2 node and run <code>/ae2gateway enroll &lt;alias&gt;</code>.</div>
      )}

      <Terminal grid={grid} />
    </div>
  )
}

export default App
