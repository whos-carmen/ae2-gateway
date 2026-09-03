package dev.ae2gateway;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class GatewayCommands {
    private GatewayCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ae2gateway")
                .then(Commands.literal("link")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(context -> link(context.getSource(),
                                        StringArgumentType.getString(context, "code")))))
                .then(Commands.literal("enroll").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("alias", StringArgumentType.greedyString())
                                .executes(context -> enroll(context.getSource(),
                                        StringArgumentType.getString(context, "alias")))))
                .then(Commands.literal("grant").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("grid", UuidArgument.uuid())
                                .then(Commands.argument("player", UuidArgument.uuid())
                                        .then(Commands.literal("read").executes(context -> acl(context.getSource(),
                                                UuidArgument.getUuid(context, "grid"),
                                                UuidArgument.getUuid(context, "player"),
                                                GatewaySavedData.Enrollment.READ, true)))
                                        .then(Commands.literal("craft").executes(context -> acl(context.getSource(),
                                                UuidArgument.getUuid(context, "grid"),
                                                UuidArgument.getUuid(context, "player"),
                                                GatewaySavedData.Enrollment.CRAFT, true))))))
                .then(Commands.literal("revoke").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("grid", UuidArgument.uuid())
                                .then(Commands.argument("player", UuidArgument.uuid())
                                        .then(Commands.literal("read").executes(context -> acl(context.getSource(),
                                                UuidArgument.getUuid(context, "grid"),
                                                UuidArgument.getUuid(context, "player"),
                                                GatewaySavedData.Enrollment.READ, false)))
                                        .then(Commands.literal("craft").executes(context -> acl(context.getSource(),
                                                UuidArgument.getUuid(context, "grid"),
                                                UuidArgument.getUuid(context, "player"),
                                                GatewaySavedData.Enrollment.CRAFT, false)))))));
    }

    private static int enroll(CommandSourceStack source, String alias) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            GatewayCoordinator coordinator = requireCoordinator(source);
            HitResult hit = player.pick(8.0, 0.0F, false);
            if (!(hit instanceof BlockHitResult blockHit)) {
                throw new GatewayCoordinator.ClientException(400, "no_target", "Look at an AE2 grid node");
            }
            IInWorldGridNodeHost host = GridHelper.getNodeHost(player.serverLevel(), blockHit.getBlockPos());
            IGridNode node = host == null ? null : host.getGridNode(blockHit.getDirection());
            if (node == null) {
                node = GridHelper.getExposedNode(player.serverLevel(), blockHit.getBlockPos(),
                        blockHit.getDirection());
            }
            if (node == null) {
                throw new GatewayCoordinator.ClientException(400, "no_target",
                        "The targeted block face has no AE2 grid node");
            }
            GatewayCoordinator.EnrollmentResult result = coordinator.enroll(node, alias, player.getUUID());
            source.sendSuccess(() -> Component.literal((result.created() ? "Enrolled grid " : "Updated grid ")
                    + result.id()), false);
            return 1;
        } catch (GatewayCoordinator.ClientException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("AE2 Gateway command failed"));
            return 0;
        }
    }

    private static int acl(CommandSourceStack source, UUID grid, UUID player, int permission, boolean grant) {
        try {
            GatewayCoordinator coordinator = requireCoordinator(source);
            UUID operator = source.getPlayerOrException().getUUID();
            coordinator.setAcl(grid, player, permission, grant, operator);
            source.sendSuccess(() -> Component.literal((grant ? "Granted " : "Revoked ")
                    + permissionName(permission) + " for " + player), true);
            return 1;
        } catch (GatewayCoordinator.ClientException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("AE2 Gateway command failed"));
            return 0;
        }
    }

    private static int link(CommandSourceStack source, String code) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            requireCoordinator(source).consumeLinkCode(code, player);
            source.sendSuccess(() -> Component.literal("Dashboard identity linked"), false);
            return 1;
        } catch (GatewayCoordinator.ClientException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("AE2 Gateway command failed"));
            return 0;
        }
    }

    private static GatewayCoordinator requireCoordinator(CommandSourceStack source) {
        GatewayCoordinator coordinator = GatewayMod.coordinator(source.getServer());
        if (coordinator == null) {
            throw new GatewayCoordinator.ClientException(503, "unavailable", "AE2 Gateway is unavailable");
        }
        return coordinator;
    }

    private static String permissionName(int permission) {
        return permission == GatewaySavedData.Enrollment.CRAFT ? "craft" : "read";
    }
}
