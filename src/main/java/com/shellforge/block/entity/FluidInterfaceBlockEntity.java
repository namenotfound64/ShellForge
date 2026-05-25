package com.shellforge.block.entity;

import com.shellforge.registry.SFBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

public class FluidInterfaceBlockEntity extends BlockEntity {
    public FluidInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.FLUID_INTERFACE.get(), pos, state);
    }

    public @Nullable IFluidHandler getFluidHandler(@Nullable Direction side) {
        if (!(level instanceof ServerLevel serverLevel) || side == null) {
            return null;
        }
        BlockPos neighborPos = worldPosition.relative(side);
        return serverLevel.getCapability(Capabilities.FluidHandler.BLOCK, neighborPos, side.getOpposite());
    }
}
