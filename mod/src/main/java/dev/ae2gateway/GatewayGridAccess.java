package dev.ae2gateway;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridService;

/**
 * Public AE2 grid service exposed through {@code IGrid#getService}.
 */
public interface GatewayGridAccess extends IGridService {
    IGrid grid();
}
