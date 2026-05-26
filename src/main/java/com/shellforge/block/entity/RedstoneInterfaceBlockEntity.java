package com.shellforge.block.entity;

import com.shellforge.block.RedstoneInterfaceBlock;
import com.shellforge.registry.SFBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RedstoneInterfaceBlockEntity extends BlockEntity {
    private int externalPower;

    public RedstoneInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.REDSTONE_INTERFACE.get(), pos, state);
    }

    /**
     * Apply shell-face power at the same analog level read from the external side (0–15).
     */
    public void applyExternalPower(int power, Direction shellFace) {
        int clamped = Mth.clamp(power, 0, 15);
        if (clamped == externalPower) {
            return;
        }
        externalPower = clamped;
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

    public int getExternalPower() {
        return externalPower;
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
            // Ignore neighbors powered only by this interface's inbound shell signal.
            if (signal > externalPower && signal > power) {
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
        tag.putInt("ExternalPower", externalPower);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ExternalPower")) {
            externalPower = Mth.clamp(tag.getInt("ExternalPower"), 0, 15);
        } else if (tag.getBoolean("StronglyPowered")) {
            externalPower = 15;
        }
    }
}
