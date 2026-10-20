package com.shellforge.mixin;

import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.content.ShellFluidFacePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirect Create pipe fluid endpoint discovery from the shell block body to
 * per-face virtual fluid nodes, keeping independent shell faces from merging.
 */
@Mixin(com.simibubi.create.content.fluids.FluidPropagator.class)
public abstract class FluidPropagatorMixin {

    @Redirect(
            method = "hasFluidCapability",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
            )
    )
    private static BlockEntity shellforge$getFluidFaceBlockEntity(
            BlockGetter targetWorld,
            BlockPos targetPos,
            BlockGetter world,
            BlockPos pos,
            Direction side
    ) {
        BlockEntity blockEntity = targetWorld.getBlockEntity(targetPos);
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock
                && shellBlock.hasFluidOnFace(side)) {
            return targetWorld.getBlockEntity(new ShellFluidFacePos(targetPos, side));
        }
        return blockEntity;
    }
}
