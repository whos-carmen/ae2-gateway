package dev.ae2gateway;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.PlayerSource;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.loading.FMLPaths;

final class GatewayCoordinator implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;
    final GatewayConfig config;
    private final GatewaySavedData data;
    private final ArrayBlockingQueue<QueuedTask<?>> queue;
    private final AtomicLong revision = new AtomicLong();
    private final Map<UUID, GridSnapshot> snapshots = new HashMap<>();
    private final Map<IGrid, Long> lastGridSnapshots = new IdentityHashMap<>();
    private final Map<UUID, PlanEntry> plans = new LinkedHashMap<>();
    private final Map<UUID, RequestEntry> requests = new LinkedHashMap<>();
    private final Map<String, LinkCodeEntry> linkCodes = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final BufferedWriter audit;
    private long ticks;

    GatewayCoordinator(MinecraftServer server, GatewayConfig config) throws IOException {
        this.server = server;
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(config.maxQueuedTasks);
        this.data = server.overworld().getDataStorage().computeIfAbsent(
                GatewaySavedData::load, GatewaySavedData::new, GatewaySavedData.NAME);
        Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("ae2-gateway-audit.jsonl");
        Files.createDirectories(log.getParent());
        this.audit = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    <T> T onServerThread(Callable<T> callable) throws Exception {
        if (server.isSameThread()) {
            return callable.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!queue.offer(new QueuedTask<>(callable, result))) {
            throw new QueueFullException();
        }
        try {
            return result.get(config.requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            result.cancel(false);
            throw exception;
        }
    }

    void tick() {
        ticks++;
        long deadline = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(config.tickBudgetMicros);
        int completed = 0;
        while (completed < config.maxTasksPerTick && System.nanoTime() < deadline) {
            QueuedTask<?> task = queue.poll();
            if (task == null) {
                break;
            }
            task.run();
            completed++;
        }
        pollPlans(deadline);
        if (ticks % 20 == 0) {
            Instant now = Instant.now();
            linkCodes.values().removeIf(code -> CoreLogic.isExpired(code.expiresAt, now));
            cleanupRetention(now);
        }
    }

    void onGridTick(GatewayGridService service) {
        if (ticks % config.snapshotIntervalTicks != 0
                || lastGridSnapshots.getOrDefault(service.grid(), -1L) == ticks) {
            return;
        }
        GatewaySavedData.Enrollment enrollment = enrollmentForGrid(service.grid());
        if (enrollment == null) {
            return;
        }
        lastGridSnapshots.put(service.grid(), ticks);
        snapshots.put(enrollment.id, capture(enrollment, service.grid()));
    }

    EnrollmentResult enroll(IGridNode node, String alias, UUID operator) {
        validateAlias(alias);
        ServerLevel level = node.getLevel();
        BlockPos pos = ownerPosition(node);
        if (pos == null) {
            throw new ClientException(400, "unsupported_anchor", "The targeted AE2 node has no block anchor");
        }
        Direction side = findSide(level, pos, node);
        for (GatewaySavedData.Enrollment existing : data.grids.values()) {
            if (existing.dimension.equals(level.dimension().location().toString())
                    && existing.position == pos.asLong() && existing.side.equals(side.getName())) {
                existing.alias = alias;
                existing.acl.put(operator, GatewaySavedData.Enrollment.READ | GatewaySavedData.Enrollment.CRAFT);
                data.setDirty();
                audit("grid_reenrolled", operator.toString(), existing.id.toString(), "ok");
                return new EnrollmentResult(existing.id, false);
            }
        }
        for (GatewaySavedData.Enrollment existing : data.grids.values()) {
            ResolvedGrid resolved = resolve(existing);
            if (resolved != null && resolved.grid == node.getGrid()) {
                existing.alias = alias;
                existing.dimension = level.dimension().location().toString();
                existing.position = pos.asLong();
                existing.side = side.getName();
                existing.acl.put(operator, GatewaySavedData.Enrollment.READ | GatewaySavedData.Enrollment.CRAFT);
                data.setDirty();
                snapshots.put(existing.id, capture(existing, node.getGrid()));
                audit("grid_anchor_updated", operator.toString(), existing.id.toString(), "ok");
                return new EnrollmentResult(existing.id, false);
            }
        }
        UUID id = UUID.randomUUID();
        GatewaySavedData.Enrollment enrollment = new GatewaySavedData.Enrollment(id, alias,
                level.dimension().location().toString(), pos.asLong(), side.getName());
        enrollment.acl.put(operator, GatewaySavedData.Enrollment.READ | GatewaySavedData.Enrollment.CRAFT);
        data.grids.put(id, enrollment);
        data.setDirty();
        snapshots.put(id, capture(enrollment, node.getGrid()));
        audit("grid_enrolled", operator.toString(), id.toString(), "ok");
        return new EnrollmentResult(id, true);
    }

    void setAcl(UUID gridId, UUID player, int permission, boolean grant, UUID operator) {
        GatewaySavedData.Enrollment enrollment = requireEnrollment(gridId);
        int old = enrollment.acl.getOrDefault(player, 0);
        int mask = permission == GatewaySavedData.Enrollment.CRAFT
                ? GatewaySavedData.Enrollment.READ | GatewaySavedData.Enrollment.CRAFT : permission;
        int updated = grant ? old | mask : old & ~permission;
        if (updated == 0) {
            enrollment.acl.remove(player);
        } else {
            enrollment.acl.put(player, updated);
        }
        data.setDirty();
        audit(grant ? "acl_grant" : "acl_revoke", operator.toString(), gridId.toString(),
                player + ":" + permission);
    }

    List<GridView> listGrids(Identity identity) {
        GatewaySavedData.IdentityLink link = linked(identity);
        List<GridView> result = new ArrayList<>();
        for (GatewaySavedData.Enrollment enrollment : data.grids.values()) {
            if (!enrollment.permits(link.player(), GatewaySavedData.Enrollment.READ)) {
                continue;
            }
            ResolvedGrid resolved = resolve(enrollment);
            GridSnapshot snapshot = snapshots.get(enrollment.id);
            boolean online = resolved != null && resolved.node.isOnline();
            boolean booting = resolved != null && resolved.grid.getPathingService().isNetworkBooting();
            String state = snapshot == null ? "unknown" : snapshot.controllerState;
            List<String> permissions = new ArrayList<>();
            permissions.add("read");
            if (enrollment.permits(link.player(), GatewaySavedData.Enrollment.CRAFT)) {
                permissions.add("craft");
            }
            result.add(new GridView(enrollment.id, enrollment.alias, online, booting, state,
                    List.copyOf(permissions)));
        }
        result.sort(Comparator.comparing(GridView::alias));
        return List.copyOf(result);
    }

    GridSnapshot requireSnapshot(Identity identity, UUID gridId, int permission) {
        authorize(identity, gridId, permission);
        GridSnapshot snapshot = snapshots.get(gridId);
        if (snapshot == null) {
            GatewaySavedData.Enrollment enrollment = requireEnrollment(gridId);
            ResolvedGrid resolved = resolve(enrollment);
            if (resolved == null) {
                throw new ClientException(503, "grid_offline", "Grid anchor is unavailable");
            }
            snapshot = capture(enrollment, resolved.grid);
            snapshots.put(gridId, snapshot);
        }
        return snapshot;
    }

    LinkCodeView createLinkCode(Identity identity) {
        GatewaySavedData.IdentityLink existing = data.identities.get(identity.subject);
        if (existing != null) {
            throw new ClientException(409, "already_linked", "Identity is already linked");
        }
        String code;
        do {
            code = CoreLogic.newLinkCode(random);
        } while (linkCodes.containsKey(code));
        Instant expires = Instant.now().plus(5, ChronoUnit.MINUTES);
        linkCodes.put(code, new LinkCodeEntry(identity, expires));
        audit("link_code_created", identity.subject, null, "ok");
        return new LinkCodeView(code, expires.toString());
    }

    void consumeLinkCode(String rawCode, ServerPlayer player) {
        String code = CoreLogic.normalizeCode(rawCode);
        LinkCodeEntry pending = linkCodes.remove(code);
        if (pending == null || CoreLogic.isExpired(pending.expiresAt, Instant.now())) {
            throw new ClientException(400, "invalid_link_code", "Link code is invalid or expired");
        }
        data.identities.entrySet().removeIf(entry -> entry.getValue().player().equals(player.getUUID()));
        data.identities.put(pending.identity.subject, new GatewaySavedData.IdentityLink(
                pending.identity.subject, pending.identity.email, player.getUUID(),
                player.getGameProfile().getName()));
        data.setDirty();
        audit("identity_linked", pending.identity.subject, null, player.getUUID().toString());
    }

    IdentityView identityView(Identity identity) {
        GatewaySavedData.IdentityLink link = data.identities.get(identity.subject);
        return new IdentityView(identity.subject, identity.email, link != null,
                link == null ? null : link.player().toString(), link == null ? null : link.playerName());
    }

    PlanView createPlan(Identity identity, UUID gridId, String token, long amount) {
        Instant now = Instant.now();
        cleanupRetention(now);
        if (plans.size() >= config.maxActivePlans) {
            throw new ClientException(503, "plan_capacity_reached",
                    "Crafting plan capacity is temporarily exhausted");
        }
        if (amount < 1 || amount > config.maxCraftAmount) {
            throw new ClientException(400, "invalid_amount", "Craft amount is outside configured limits");
        }
        GatewaySavedData.IdentityLink link = authorize(identity, gridId, GatewaySavedData.Enrollment.CRAFT);
        GridSnapshot snapshot = requireSnapshot(identity, gridId, GatewaySavedData.Enrollment.CRAFT);
        AEKey key = snapshot.keysByToken.get(token);
        if (key == null) {
            throw new ClientException(400, "invalid_key_token", "Item token is invalid or stale");
        }
        GatewaySavedData.Enrollment enrollment = requireEnrollment(gridId);
        ResolvedGrid resolved = resolve(enrollment);
        if (resolved == null || !resolved.node.isActive()) {
            throw new ClientException(503, "grid_offline", "Grid is not active");
        }
        IActionSource source = craftingActionSource(craftingActor(link, resolved.node), resolved.node);
        ICraftingSimulationRequester requester = craftingRequester(source, resolved.node);
        Future<ICraftingPlan> future = resolved.grid.getCraftingService().beginCraftingCalculation(
                resolved.node.getLevel(), requester, key, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        UUID planId = UUID.randomUUID();
        Instant expires = now.plus(config.planTtlSeconds, ChronoUnit.SECONDS);
        PlanEntry entry = new PlanEntry(planId, gridId, identity.subject, link.player(), key, amount,
                GatewayDisplayNames.of(key), expires,
                expires.plus(config.planRetentionSeconds, ChronoUnit.SECONDS), resolved.grid, source, future);
        plans.put(planId, entry);
        audit("craft_plan_created", identity.subject, gridId.toString(), planId.toString());
        return entry.view();
    }

    PlanView getPlan(Identity identity, UUID planId) {
        cleanupRetention(Instant.now());
        PlanEntry entry = requireOwnedPlan(identity, planId);
        expire(entry);
        return entry.view();
    }

    RequestView submitPlan(Identity identity, UUID planId, String idempotencyKey) {
        cleanupRetention(Instant.now());
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ClientException(400, "invalid_idempotency_key", "Idempotency-Key must be 16-128 characters");
        }
        PlanEntry entry = requireOwnedPlan(identity, planId);
        if (entry.idempotencyKey != null) {
            if (!CoreLogic.constantTimeEquals(entry.idempotencyKey, idempotencyKey)) {
                throw new ClientException(409, "plan_already_submitted",
                        "Plan was submitted with a different idempotency key");
            }
            RequestEntry prior = requests.get(entry.requestId);
            if (prior == null) {
                throw new ClientException(410, "idempotency_result_expired",
                        "The retained result for this idempotency key has expired");
            }
            return prior.view;
        }
        expire(entry);
        if (!"ready".equals(entry.state)) {
            throw new ClientException(409, "plan_not_ready", "Plan is not ready for submission");
        }
        GatewaySavedData.IdentityLink link = authorize(identity, entry.gridId, GatewaySavedData.Enrollment.CRAFT);
        if (!Objects.equals(link.player(), entry.player)) {
            throw new ClientException(403, "identity_changed", "Linked Minecraft identity changed");
        }
        ResolvedGrid resolved = resolve(requireEnrollment(entry.gridId));
        if (resolved == null || resolved.grid != entry.grid || !resolved.node.isActive()) {
            throw new ClientException(409, "grid_changed", "Grid identity changed after planning");
        }
        Instant now = Instant.now();
        cleanupRetention(now);
        if (requests.size() >= config.maxRetainedRequests) {
            throw new ClientException(503, "request_capacity_reached",
                    "Crafting request capacity is temporarily exhausted");
        }
        UUID requestId = UUID.randomUUID();
        RequestView accepted = new RequestView(requestId, planId, "accepted", now.toString(), null);
        RequestEntry retained = new RequestEntry(identity.subject, accepted,
                now.plus(config.requestRetentionSeconds, ChronoUnit.SECONDS));
        requests.put(requestId, retained);
        entry.idempotencyKey = idempotencyKey;
        entry.requestId = requestId;

        ICraftingSubmitResult submitted;
        try {
            submitted = resolved.grid.getCraftingService().submitJob(entry.aePlan, null, null,
                    true, craftingActionSource(craftingActor(link, resolved.node), resolved.node));
        } catch (RuntimeException exception) {
            entry.state = "failed";
            entry.error = "AE2 crafting submission failed";
            RequestView rejected = new RequestView(requestId, planId, "rejected", accepted.createdAt,
                    "AE2 crafting submission failed");
            requests.put(requestId, new RequestEntry(identity.subject, rejected, retained.retainUntil));
            audit("craft_rejected", identity.subject, entry.gridId.toString(),
                    exception.getClass().getSimpleName());
            return rejected;
        }
        RequestView result;
        if (submitted.successful()) {
            entry.state = "submitted";
            result = new RequestView(requestId, planId, "submitted", accepted.createdAt, null);
            audit("craft_submitted", identity.subject, entry.gridId.toString(), requestId.toString());
        } else {
            entry.state = "failed";
            entry.error = "AE2 rejected the crafting request";
            result = new RequestView(requestId, planId, "rejected", accepted.createdAt,
                    "AE2 rejected the crafting request: " + submitted.errorCode().name().toLowerCase(Locale.ROOT));
            audit("craft_rejected", identity.subject, entry.gridId.toString(), submitted.errorCode().name());
        }
        requests.put(requestId, new RequestEntry(identity.subject, result, retained.retainUntil));
        return result;
    }

    RequestView getRequest(Identity identity, UUID requestId) {
        cleanupRetention(Instant.now());
        RequestEntry request = requests.get(requestId);
        if (request == null) {
            throw new ClientException(404, "not_found", "Crafting request was not found");
        }
        if (!CoreLogic.constantTimeEquals(request.subject, identity.subject)) {
            throw new ClientException(403, "forbidden", "Crafting request permission denied");
        }
        return request.view;
    }

    private void pollPlans(long deadline) {
        Instant now = Instant.now();
        for (PlanEntry entry : plans.values()) {
            if (System.nanoTime() >= deadline) {
                return;
            }
            expire(entry);
            if (!"calculating".equals(entry.state) || !entry.future.isDone()) {
                continue;
            }
            try {
                ICraftingPlan plan = entry.future.get();
                entry.aePlan = plan;
                entry.bytes = plan.bytes();
                entry.simulation = plan.simulation();
                PlanSummaryViews summary = summarizePlan(entry.grid, entry.actionSource, plan);
                entry.entries = summary.entries();
                entry.missing = summary.missing();
                if (entry.bytes > config.maxCraftBytes) {
                    entry.state = "failed";
                    entry.error = "Crafting plan exceeds configured byte limit";
                } else if (plan.simulation() || !entry.missing.isEmpty()) {
                    entry.state = "missing";
                } else {
                    entry.state = "ready";
                }
            } catch (Exception exception) {
                entry.state = "failed";
                entry.error = "Crafting calculation failed";
                audit("craft_plan_failed", entry.subject, entry.gridId.toString(), exception.getClass().getSimpleName());
            }
        }
    }

    private GridSnapshot capture(GatewaySavedData.Enrollment enrollment, IGrid grid) {
        long deadline = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(config.tickBudgetMicros);
        Map<AEKey, Long> amounts = new HashMap<>();
        for (Object2LongMap.Entry<AEKey> stack : grid.getStorageService().getCachedInventory()) {
            amounts.put(stack.getKey(), stack.getLongValue());
            if (amounts.size() >= config.maxSnapshotKeys) {
                break;
            }
        }
        Map<AEKey, Boolean> craftable = new HashMap<>();
        for (AEKey key : grid.getCraftingService().getCraftables(key -> true)) {
            craftable.put(key, true);
            if (amounts.size() + craftable.size() >= config.maxSnapshotKeys
                    || System.nanoTime() >= deadline) {
                break;
            }
        }
        Map<String, AEKey> keys = new LinkedHashMap<>();
        List<ItemView> items = new ArrayList<>();
        Map<AEKey, Boolean> all = new HashMap<>(craftable);
        amounts.keySet().forEach(key -> all.putIfAbsent(key, false));
        for (Map.Entry<AEKey, Boolean> entry : all.entrySet()) {
            AEKey key = entry.getKey();
            String token = CoreLogic.opaqueToken(config.serviceSecret, enrollment.id, key.toTagGeneric().toString());
            keys.put(token, key);
            String type = key instanceof AEItemKey ? "item" : key instanceof AEFluidKey ? "fluid" : "other";
            items.add(new ItemView(token, type, key.getId().toString(), GatewayDisplayNames.of(key),
                    key.getModId(), amounts.getOrDefault(key, 0L), entry.getValue()));
        }
        items.sort(Comparator.comparing(ItemView::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ItemView::keyToken));

        List<CpuView> cpus = new ArrayList<>();
        int index = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            cpus.add(toCpuView(enrollment, cpu, index++));
        }
        var energy = grid.getEnergyService();
        var pathing = grid.getPathingService();
        long nextRevision = revision.incrementAndGet();
        MetricsView metrics = new MetricsView(nextRevision, energy.isNetworkPowered(), energy.getStoredPower(),
                energy.getMaxStoredPower(), energy.getAvgPowerUsage(), energy.getAvgPowerInjection(),
                pathing.getUsedChannels(), grid.size(), amounts.size(), craftable.size());
        String controller = switch (pathing.getControllerState()) {
            case CONTROLLER_ONLINE -> "online";
            case CONTROLLER_CONFLICT -> "conflict";
            case NO_CONTROLLER -> "no_controller";
        };
        return new GridSnapshot(nextRevision, List.copyOf(items), List.copyOf(cpus), metrics, controller,
                Map.copyOf(keys));
    }

    CpuDetailView cpuDetail(Identity identity, UUID gridId, String cpuId) {
        authorize(identity, gridId, GatewaySavedData.Enrollment.READ);
        GatewaySavedData.Enrollment enrollment = requireEnrollment(gridId);
        ResolvedGrid resolved = resolve(enrollment);
        if (resolved == null || !resolved.node.isActive()) {
            throw new ClientException(503, "grid_offline", "Grid is not active");
        }
        int index = 0;
        for (ICraftingCPU cpu : resolved.grid.getCraftingService().getCpus()) {
            CpuView summary = toCpuView(enrollment, cpu, index++);
            if (!CoreLogic.constantTimeEquals(summary.id(), cpuId)) {
                continue;
            }
            List<CpuItemView> entries = List.of();
            boolean suspended = false;
            if (cpu instanceof CraftingCPUCluster cluster) {
                CraftingCpuLogic logic = cluster.craftingLogic;
                suspended = logic.isJobSuspended();
                KeyCounter keys = new KeyCounter();
                logic.getAllItems(keys);
                List<CpuItemView> built = new ArrayList<>();
                for (Object2LongMap.Entry<AEKey> stack : keys) {
                    AEKey key = stack.getKey();
                    long stored = logic.getStored(key);
                    long crafting = logic.getWaitingFor(key);
                    long scheduled = logic.getPendingOutputs(key);
                    if (stored == 0 && crafting == 0 && scheduled == 0) {
                        continue;
                    }
                    built.add(new CpuItemView(GatewayDisplayNames.of(key), key.getId().toString(),
                            stored, crafting, scheduled));
                }
                built.sort(Comparator
                        .comparingLong((CpuItemView entry) -> entry.crafting() + entry.scheduled()).reversed()
                        .thenComparingLong(CpuItemView::stored).reversed()
                        .thenComparing(CpuItemView::displayName, String.CASE_INSENSITIVE_ORDER));
                entries = List.copyOf(built);
            }
            return new CpuDetailView(summary, suspended, entries);
        }
        throw new ClientException(404, "cpu_not_found", "Crafting CPU was not found");
    }

    private CpuView toCpuView(GatewaySavedData.Enrollment enrollment, ICraftingCPU cpu, int index) {
        CraftingJobStatus status = cpu.getJobStatus();
        String name = cpu.getName() == null ? "" : cpu.getName().getString();
        String cpuId = CoreLogic.opaqueToken(config.serviceSecret, enrollment.id,
                "cpu:" + index + ":" + name + ":" + cpu.getAvailableStorage());
        String output = status == null ? null : GatewayDisplayNames.of(status.crafting().what());
        Double progress = status == null || status.totalItems() == 0 ? null
                : Math.min(1.0, (double) status.progress() / status.totalItems());
        Long elapsed = status == null ? null : TimeUnit.NANOSECONDS.toMillis(status.elapsedTimeNanos());
        return new CpuView(cpuId, name, cpu.isBusy(), output, progress, elapsed,
                cpu.getAvailableStorage(), cpu.getCoProcessors());
    }

    private GatewaySavedData.Enrollment enrollmentForGrid(IGrid grid) {
        for (GatewaySavedData.Enrollment enrollment : data.grids.values()) {
            ResolvedGrid resolved = resolve(enrollment);
            if (resolved != null && resolved.grid == grid) {
                return enrollment;
            }
        }
        return null;
    }

    private ResolvedGrid resolve(GatewaySavedData.Enrollment enrollment) {
        try {
            ResourceLocation dimension = ResourceLocation.tryParse(enrollment.dimension);
            if (dimension == null) {
                return null;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null || !level.hasChunkAt(BlockPos.of(enrollment.position))) {
                return null;
            }
            IInWorldGridNodeHost host = GridHelper.getNodeHost(level, BlockPos.of(enrollment.position));
            Direction side = Direction.byName(enrollment.side);
            IGridNode node = host == null || side == null ? null : host.getGridNode(side);
            if (node == null) {
                return null;
            }
            GatewayGridAccess access = node.getGrid().getService(GatewayGridAccess.class);
            return access == null ? null : new ResolvedGrid(access.grid(), node);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private GatewaySavedData.IdentityLink authorize(Identity identity, UUID gridId, int permission) {
        GatewaySavedData.IdentityLink link = linked(identity);
        GatewaySavedData.Enrollment enrollment = requireEnrollment(gridId);
        if (!enrollment.permits(link.player(), permission)) {
            audit("authorization_denied", identity.subject, gridId.toString(), Integer.toString(permission));
            throw new ClientException(403, "forbidden", "Grid permission denied");
        }
        return link;
    }

    private GatewaySavedData.IdentityLink linked(Identity identity) {
        GatewaySavedData.IdentityLink link = data.identities.get(identity.subject);
        if (link == null) {
            throw new ClientException(403, "identity_not_linked", "Identity is not linked to Minecraft");
        }
        return link;
    }

    private GatewaySavedData.Enrollment requireEnrollment(UUID id) {
        GatewaySavedData.Enrollment enrollment = data.grids.get(id);
        if (enrollment == null) {
            throw new ClientException(404, "not_found", "Grid was not found");
        }
        return enrollment;
    }

    private PlanEntry requireOwnedPlan(Identity identity, UUID id) {
        PlanEntry entry = plans.get(id);
        if (entry == null) {
            throw new ClientException(404, "not_found", "Crafting plan was not found");
        }
        if (!CoreLogic.constantTimeEquals(entry.subject, identity.subject)) {
            throw new ClientException(403, "forbidden", "Crafting plan permission denied");
        }
        return entry;
    }

    private void expire(PlanEntry entry) {
        if (!"submitted".equals(entry.state) && CoreLogic.isExpired(entry.expiresAt, Instant.now())) {
            entry.state = "expired";
            entry.aePlan = null;
            entry.future.cancel(false);
        }
    }

    private void cleanupRetention(Instant now) {
        var planIterator = plans.entrySet().iterator();
        while (planIterator.hasNext()) {
            PlanEntry plan = planIterator.next().getValue();
            if (!CoreLogic.isExpired(plan.retainUntil, now)) {
                break;
            }
            plan.future.cancel(false);
            plan.aePlan = null;
            planIterator.remove();
        }
        var requestIterator = requests.entrySet().iterator();
        while (requestIterator.hasNext()) {
            RequestEntry request = requestIterator.next().getValue();
            if (!CoreLogic.isExpired(request.retainUntil, now)) {
                break;
            }
            requestIterator.remove();
        }
    }

    /**
     * Prefer a live player; otherwise a Forge FakePlayer using the linked profile UUID so AE2
     * security still attributes the action to that player without requiring a client session.
     */
    private Player craftingActor(GatewaySavedData.IdentityLink link, IGridNode node) {
        ServerPlayer online = server.getPlayerList().getPlayer(link.player());
        if (online != null) {
            return online;
        }
        ServerLevel level = node.getLevel();
        if (level == null) {
            level = server.overworld();
        }
        GameProfile profile = server.getProfileCache() == null ? null
                : server.getProfileCache().get(link.player()).orElse(null);
        if (profile == null) {
            String name = link.playerName() == null || link.playerName().isBlank() ? "AE2Gateway" : link.playerName();
            if (name.length() > 16) {
                name = name.substring(0, 16);
            }
            profile = new GameProfile(link.player(), name);
        }
        return FakePlayerFactory.get(level, profile);
    }

    private static IActionSource craftingActionSource(Player player, IGridNode node) {
        Object owner = node.getOwner();
        IActionHost host = owner instanceof IActionHost actionHost ? actionHost : null;
        return new PlayerSource(player, host);
    }

    private static ICraftingSimulationRequester craftingRequester(IActionSource source, IGridNode node) {
        return new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return source;
            }

            @Override
            public IGridNode getGridNode() {
                return node;
            }
        };
    }

    private static PlanSummaryViews summarizePlan(IGrid grid, IActionSource source, ICraftingPlan plan) {
        CraftingPlanSummary summary = CraftingPlanSummary.fromJob(grid, source, plan);
        List<PlanEntryView> entries = new ArrayList<>();
        List<IngredientView> missing = new ArrayList<>();
        for (CraftingPlanSummaryEntry entry : summary.getEntries()) {
            long missingAmount = entry.getMissingAmount();
            PlanEntryView view = new PlanEntryView(
                    GatewayDisplayNames.of(entry.getWhat()),
                    entry.getStoredAmount(),
                    entry.getCraftAmount(),
                    missingAmount);
            entries.add(view);
            if (missingAmount > 0) {
                missing.add(new IngredientView(view.displayName(), missingAmount));
            }
        }
        return new PlanSummaryViews(List.copyOf(entries), List.copyOf(missing));
    }

    private static BlockPos ownerPosition(IGridNode node) {
        Object owner = node.getOwner();
        return owner instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity
                ? blockEntity.getBlockPos() : null;
    }

    private static Direction findSide(ServerLevel level, BlockPos pos, IGridNode target) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host != null) {
            for (Direction side : Direction.values()) {
                if (host.getGridNode(side) == target) {
                    return side;
                }
            }
        }
        throw new ClientException(400, "unsupported_anchor", "Could not determine an exposed AE2 node side");
    }

    private static void validateAlias(String alias) {
        if (alias == null || alias.isBlank() || alias.length() > 64
                || alias.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw new ClientException(400, "invalid_alias", "Alias must be 1-64 printable characters");
        }
    }

    private synchronized void audit(String event, String actor, String grid, String result) {
        try {
            JsonObject line = new JsonObject();
            line.addProperty("at", Instant.now().toString());
            line.addProperty("event", event);
            line.addProperty("actor", actor);
            if (grid != null) {
                line.addProperty("gridId", grid);
            }
            line.addProperty("result", result);
            audit.write(GSON.toJson(line));
            audit.newLine();
            audit.flush();
        } catch (IOException ignored) {
            // Audit failures are reported by the caller's primary operation only in server logs.
        }
    }

    @Override
    public void close() {
        QueuedTask<?> task;
        while ((task = queue.poll()) != null) {
            task.fail(new IllegalStateException("server stopping"));
        }
        try {
            audit.close();
        } catch (IOException ignored) {
        }
    }

    record Identity(String subject, String email) {
    }

    record IdentityView(String subject, String email, boolean linked, String minecraftUuid,
            String minecraftName) {
    }

    record LinkCodeView(String code, String expiresAt) {
    }

    record GridView(UUID id, String alias, boolean online, boolean booting, String controllerState,
            List<String> permissions) {
    }

    record ItemView(String keyToken, String type, String resourceId, String displayName, String namespace,
            long amount, boolean craftable) {
    }

    record CpuView(String id, String name, boolean busy, String outputName, Double progress,
            Long elapsedMillis, long storageBytes, int coprocessors) {
    }

    record CpuItemView(String displayName, String resourceId, long stored, long crafting, long scheduled) {
    }

    record CpuDetailView(CpuView cpu, boolean suspended, List<CpuItemView> entries) {
    }

    record MetricsView(long revision, boolean powered, double storedPower, double maxStoredPower,
            double averagePowerUsage, double averagePowerInjection, int usedChannels, int deviceCount,
            int storedItemTypes, int craftableItemTypes) {
    }

    record GridSnapshot(long revision, List<ItemView> items, List<CpuView> cpus, MetricsView metrics,
            String controllerState, Map<String, AEKey> keysByToken) {
    }

    record IngredientView(String displayName, long amount) {
    }

    record PlanEntryView(String displayName, long stored, long craft, long missing) {
    }

    private record PlanSummaryViews(List<PlanEntryView> entries, List<IngredientView> missing) {
    }

    record PlanView(UUID id, UUID gridId, String state, String requestedName, long amount, Long bytes,
            String expiresAt, boolean simulation, List<PlanEntryView> entries, List<IngredientView> missing,
            String error) {
    }

    record RequestView(UUID id, UUID planId, String state, String createdAt, String error) {
    }

    record EnrollmentResult(UUID id, boolean created) {
    }

    static final class ClientException extends RuntimeException {
        final int status;
        final String code;

        ClientException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    static final class QueueFullException extends Exception {
    }

    private record ResolvedGrid(IGrid grid, IGridNode node) {
    }

    private record LinkCodeEntry(Identity identity, Instant expiresAt) {
    }

    private record RequestEntry(String subject, RequestView view, Instant retainUntil) {
    }

    private static final class PlanEntry {
        final UUID id;
        final UUID gridId;
        final String subject;
        final UUID player;
        final AEKey key;
        final long amount;
        final String requestedName;
        final Instant expiresAt;
        final Instant retainUntil;
        final IGrid grid;
        final IActionSource actionSource;
        final Future<ICraftingPlan> future;
        String state = "calculating";
        Long bytes;
        boolean simulation;
        List<PlanEntryView> entries = List.of();
        List<IngredientView> missing = List.of();
        String error;
        ICraftingPlan aePlan;
        String idempotencyKey;
        UUID requestId;

        PlanEntry(UUID id, UUID gridId, String subject, UUID player, AEKey key, long amount,
                String requestedName, Instant expiresAt, Instant retainUntil, IGrid grid,
                IActionSource actionSource, Future<ICraftingPlan> future) {
            this.id = id;
            this.gridId = gridId;
            this.subject = subject;
            this.player = player;
            this.key = key;
            this.amount = amount;
            this.requestedName = requestedName;
            this.expiresAt = expiresAt;
            this.retainUntil = retainUntil;
            this.grid = grid;
            this.actionSource = actionSource;
            this.future = future;
        }

        PlanView view() {
            return new PlanView(id, gridId, state, requestedName, amount, bytes, expiresAt.toString(),
                    simulation, entries, missing, error);
        }
    }

    private record QueuedTask<T>(Callable<T> callable, CompletableFuture<T> result) {
        void run() {
            if (result.isCancelled()) {
                return;
            }
            try {
                result.complete(callable.call());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }

        void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }
}
