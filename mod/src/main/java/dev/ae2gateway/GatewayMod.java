package dev.ae2gateway;

import appeng.api.networking.GridServices;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(GatewayMod.MOD_ID)
public final class GatewayMod {
    public static final String MOD_ID = "ae2_gateway";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicReference<GatewayMod> INSTANCE = new AtomicReference<>();

    private GatewayCoordinator coordinator;
    private GatewayHttpServer http;

    public GatewayMod() {
        if (!INSTANCE.compareAndSet(null, this)) {
            throw new IllegalStateException("AE2 Gateway loaded twice");
        }
        GridServices.register(GatewayGridAccess.class, GatewayGridService.class);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::serverStarted);
        MinecraftForge.EVENT_BUS.addListener(this::serverStopping);
        MinecraftForge.EVENT_BUS.addListener(this::serverTick);
    }

    static GatewayMod instance() {
        return INSTANCE.get();
    }

    void onGridTick(GatewayGridService service) {
        GatewayCoordinator current = coordinator;
        if (current != null) {
            current.onGridTick(service);
        }
    }

    private void registerCommands(RegisterCommandsEvent event) {
        GatewayCommands.register(event.getDispatcher());
    }

    private void serverStarted(ServerStartedEvent event) {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            GatewayConfig config = GatewayConfig.load(configDir);
            coordinator = new GatewayCoordinator(event.getServer(), config);
            http = new GatewayHttpServer(config, coordinator);
            http.start();
            LOGGER.info("AE2 Gateway listening on 127.0.0.1:{}", config.port);
        } catch (IOException exception) {
            coordinator = null;
            http = null;
            LOGGER.error("AE2 Gateway failed closed and did not start", exception);
        }
    }

    private void serverStopping(ServerStoppingEvent event) {
        GatewayHttpServer currentHttp = http;
        if (currentHttp != null) {
            currentHttp.close();
        }
        GatewayCoordinator currentCoordinator = coordinator;
        if (currentCoordinator != null) {
            currentCoordinator.close();
        }
        http = null;
        coordinator = null;
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && coordinator != null) {
            coordinator.tick();
        }
    }

    static GatewayCoordinator coordinator(MinecraftServer server) {
        GatewayMod mod = INSTANCE.get();
        return mod == null ? null : mod.coordinator;
    }
}
