package com.shellforge.block.entity;

import com.shellforge.block.FluidInterfaceBlock;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.CreatePipeBridgingFluidHandler;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Sublevel fluid port: {@link CreatePipeBridgingFluidHandler} buffers against Create's pipe network on
 * the interior side (pipes do not use {@link IFluidHandler}). The shell face delegates through
 * {@link com.shellforge.shell.ShellFaceFluidHandler}.
 */
public class FluidInterfaceBlockEntity extends SmartBlockEntity {
    private final CreatePipeBridgingFluidHandler bridgeHandler = new CreatePipeBridgingFluidHandler(this);

    public FluidInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.FLUID_INTERFACE.get(), pos, state);
    }

    public FluidInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        // No FluidTransportBehaviour on the interface block — interior pipes use our IFluidHandler endpoint.
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Tank")) {
            bridgeHandler.tank().readFromNBT(registries, tag.getCompound("Tank"));
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Tank", bridgeHandler.tank().writeToNBT(registries, new CompoundTag()));
    }

    /**
     * @param side face of this block being queried; interior side only
     */
    public @Nullable IFluidHandler getFluidHandler(@Nullable Direction side) {
        if (level == null || side == null) {
            return null;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(FluidInterfaceBlock.FACING)) {
            return null;
        }
        Direction interiorFace = state.getValue(FluidInterfaceBlock.FACING).getOpposite();
        if (side != interiorFace) {
            return null;
        }
        return bridgeHandler;
    }

    public void onBridgeTankChanged() {
        // Contents are read through the stable handler; only topology changes reset pipes.
        setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        notifyBoundShellFaces();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        notifyBoundShellFaces();
    }

    private void notifyBoundShellFaces() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        for (ShellBlockBlockEntity shell : ShellBlockBlockEntity.getActiveShells()) {
            if (shell.getLevel() == serverLevel) {
                shell.onInternalFluidInterfaceChanged(serverLevel, this);
            }
        }
    }
}
