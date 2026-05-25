package com.shellforge.shell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ShellDebug {
    private ShellDebug() {
    }

    public static void logPlacementPositions(
            ServerPlayer player,
            ServerLevel level,
            ShellDefinition definition,
            ShellInstance instance
    ) {
        BlockPos copyOrigin = definition.minCorner();
        BlockPos shellOrigin = instance.origin();
        BlockPos plotCenter = instance.structureWorldOrigin();

        player.displayClientMessage(Component.translatable(
                "command.shellforge.debug.placement.header",
                shellOrigin.toShortString(),
                copyOrigin.toShortString(),
                definition.size().toShortString(),
                instance.subLevelId().toString(),
                plotCenter.toShortString()
        ), false);

        for (ShellEndpoint endpoint : definition.endpoints()) {
            BlockPos relative = endpoint.internalInterfacePos();
            BlockPos originalPos = copyOrigin.offset(relative);
            BlockPos sublevelPos = ShellManager.resolveInternalGlobalPos(
                    level, instance.subLevelId(), relative);
            player.displayClientMessage(Component.translatable(
                    "command.shellforge.debug.placement.endpoint",
                    endpoint.id(),
                    endpoint.type().name().toLowerCase(),
                    endpoint.shellFace().getName(),
                    originalPos.toShortString(),
                    sublevelPos.toShortString()
            ), false);
        }

        for (ShellBlockSnapshot snapshot : definition.blocks()) {
            BlockPos relative = snapshot.relativePos();
            BlockPos originalPos = copyOrigin.offset(relative);
            BlockPos sublevelPos = ShellManager.resolveInternalGlobalPos(
                    level, instance.subLevelId(), relative);
            player.displayClientMessage(Component.translatable(
                    "command.shellforge.debug.placement.block",
                    originalPos.toShortString(),
                    sublevelPos.toShortString(),
                    BuiltInRegistries.BLOCK.getKey(snapshot.state().getBlock()).toString()
            ), false);
        }
    }
}
