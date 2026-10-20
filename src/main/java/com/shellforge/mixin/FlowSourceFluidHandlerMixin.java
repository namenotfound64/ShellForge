package com.shellforge.mixin;

import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.content.ShellFluidFacePos;
import com.simibubi.create.content.fluids.FlowSource;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BooleanSupplier;

/**
 * Keeps Create's cached fluid endpoint for shell connections pinned to the
 * queried shell face instead of the shell block's shared position.
 */
@Mixin(FlowSource.FluidHandler.class)
public abstract class FlowSourceFluidHandlerMixin {

    @Redirect(
            method = "manageSource",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
            )
    )
    private BlockEntity shellforge$getFluidFaceBlockEntity(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        Direction side = shellforge$shellFace();
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock
                && shellBlock.hasFluidOnFace(side)) {
            return level.getBlockEntity(new ShellFluidFacePos(pos, side));
        }
        return blockEntity;
    }

    @Redirect(
            method = "manageSource",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;create(Lnet/neoforged/neoforge/capabilities/BlockCapability;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/lang/Object;Ljava/util/function/BooleanSupplier;Ljava/lang/Runnable;)Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;"
            )
    )
    private <T, C> BlockCapabilityCache<T, C> shellforge$createFluidFaceCache(
            BlockCapability<T, C> capability,
            ServerLevel level,
            BlockPos pos,
            C context,
            BooleanSupplier isValid,
            Runnable invalidationListener
    ) {
        Direction side = shellforge$shellFace();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock
                && shellBlock.hasFluidOnFace(side)) {
            pos = new ShellFluidFacePos(pos, side);
        }
        return BlockCapabilityCache.create(capability, level, pos, context, isValid, invalidationListener);
    }

    private Direction shellforge$shellFace() {
        BlockFace location = ((FlowSourceAccessor) this).shellforge$getLocation();
        return location.getFace().getOpposite();
    }
}
