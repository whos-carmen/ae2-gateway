package dev.ae2gateway;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridServiceProvider;

public final class GatewayGridService implements GatewayGridAccess, IGridServiceProvider {
    private final IGrid grid;

    public GatewayGridService(IGrid grid) {
        this.grid = grid;
    }

    @Override
    public IGrid grid() {
        return grid;
    }

    @Override
    public void onServerEndTick() {
        GatewayMod instance = GatewayMod.instance();
        if (instance != null) {
            instance.onGridTick(this);
        }
    }
}
