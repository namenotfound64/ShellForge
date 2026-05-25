package com.shellforge.block.entity;

import com.shellforge.block.RedstoneInterfaceBlock;
import com.shellforge.registry.SFBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RedstoneInterfaceBlockEntity extends BlockEntity {
    private boolean stronglyPowered;

    public RedstoneInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.REDSTONE_INTERFACE.get(), pos, state);
    }

    /**
     * Apply shell-face power. Any non-zero external level strongly powers this block in the sublevel.
     */
    public void applyExternalPower(int power, Direction shellFace) {
        boolean shouldPower = power > 0;
        if (shouldPower == stronglyPowered) {
            return;
        }
        stronglyPowered = shouldPower;
        setChanged();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = getBlockState();
        if (!state.hasProperty(RedstoneInterfaceBlock.FACING)) {
            serverLevel.updateNeighborsAt(worldPosition, state.getBlock());
            return;
        }

        Direction facing = state.getValue(RedstoneInterfaceBlock.FACING);
        if (facing != shellFace) {
            state = state.setValue(RedstoneInterfaceBlock.FACING, shellFace);
            serverLevel.setBlock(worldPosition, state, Block.UPDATE_ALL);
        }

        notifyNeighbors(serverLevel, state);
    }

    public boolean isStronglyPowered() {
        return stronglyPowered;
    }

    public int getInternalOutputPower(ServerLevel serverLevel) {
        int power = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(direction);
            BlockState neighborState = serverLevel.getBlockState(neighborPos);
            int signal = Math.max(
                    neighborState.getSignal(serverLevel, neighborPos, direction),
                    neighborState.getDirectSignal(serverLevel, neighborPos, direction)
            );
            if (signal > power) {
                power = signal;
            }
        }
        return power;
    }

    private void notifyNeighbors(ServerLevel serverLevel, BlockState state) {
        serverLevel.updateNeighborsAt(worldPosition, state.getBlock());
        for (Direction direction : Direction.values()) {
            serverLevel.updateNeighborsAt(worldPosition.relative(direction), state.getBlock());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("StronglyPowered", stronglyPowered);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stronglyPowered = tag.getBoolean("StronglyPowered");
    }
}
