package com.shellforge.shell;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.EnumMap;
import java.util.Map;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** Pressure-driven endpoints for shell faces without an adjacent Create pipe. */
public final class ShellFluidOutlet {
    private final Map<Direction, OpenEndedPipe> openEnds = new EnumMap<>(Direction.class);

    public int exchange(BlockEntity shell, Direction face, IFluidHandler buffer, float pressure) {
        if (pressure >= 0) return push(shell, face, buffer, pressure);
        Level level = shell.getLevel();
        if (buffer == null || level == null || level.isClientSide() || !Float.isFinite(pressure)) return 0;
        BlockPos targetPos = shell.getBlockPos().relative(face);
        if (!level.isLoaded(targetPos) || FluidPropagator.getPipe(level, targetPos) != null) return 0;
        IFluidHandler source = level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, face.getOpposite());
        if (source == null) {
            if (!FluidPropagator.isOpenEnd(level, shell.getBlockPos(), face)) return 0;
            source = openEnd(shell, face).provideHandler().getCapability();
        }
        int moved = transfer(source, buffer, source.drain(Math.max(1, (int) (-pressure / 2)), SIMULATE));
        if (moved > 0) shell.setChanged();
        return moved;
    }

    private OpenEndedPipe openEnd(BlockEntity shell, Direction face) {
        Level level = shell.getLevel();
        BlockPos pos = shell.getBlockPos();
        OpenEndedPipe outlet = openEnds.computeIfAbsent(face, side -> new OpenEndedPipe(new BlockFace(pos, side)));
        if (!outlet.getPos().equals(pos)) {
            outlet = OpenEndedPipe.fromNBT(outlet.serializeNBT(level.registryAccess()), level.registryAccess(), pos);
            openEnds.put(face, outlet);
        }
        outlet.manageSource(level, shell);
        return outlet;
    }

    public int push(BlockEntity shell, Direction face, IFluidHandler source, float pressure) {
        Level level = shell.getLevel();
        if (source == null || level == null || level.isClientSide() || !Float.isFinite(pressure) || pressure <= 0) {
            return 0;
        }
        BlockPos pos = shell.getBlockPos();
        BlockPos targetPos = pos.relative(face);
        if (!level.isLoaded(targetPos)) {
            return 0;
        }
        // Pipes (including pumps) transfer through the shell capability themselves.
        if (FluidPropagator.getPipe(level, targetPos) != null) {
            return 0;
        }
        IFluidHandler target = level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, face.getOpposite());
        if (target == null) {
            if (!FluidPropagator.isOpenEnd(level, pos, face)) {
                return 0;
            }
            OpenEndedPipe outlet = openEnd(shell, face);
            target = outlet.provideHandler().getCapability();
            // Create clears its partial bucket on a fluid change, even during simulation.
            // Retain that fluid instead of silently discarding it when bindings share a face.
            FluidStack pending = target.getFluidInTank(0);
            if (!pending.isEmpty()) {
                FluidStack next = source.drain(pending.copyWithAmount(1), SIMULATE);
                if (next.isEmpty()) {
                    return 0;
                }
                int moved = transfer(source, target, pending.copyWithAmount(Math.max(1, (int) (pressure / 2))));
                if (moved > 0) shell.setChanged();
                return moved;
            }
        }
        int moved = transfer(source, target, source.drain(Math.max(1, (int) (pressure / 2)), SIMULATE));
        if (moved > 0) shell.setChanged();
        return moved;
    }

    static int transfer(IFluidHandler source, IFluidHandler target, FluidStack requested) {
        if (target == null || target == source || requested.isEmpty()) return 0;
        FluidStack available = source.drain(requested, SIMULATE);
        int accepted = target.fill(available.copy(), SIMULATE);
        if (accepted <= 0) return 0;
        FluidStack extracted = source.drain(available.copyWithAmount(Math.min(accepted, available.getAmount())), EXECUTE);
        int filled = target.fill(extracted.copy(), EXECUTE);
        // Our source is an interface buffer: any rejected remainder fits back into it.
        if (filled < extracted.getAmount()) {
            source.fill(extracted.copyWithAmount(extracted.getAmount() - filled), EXECUTE);
        }
        return filled;
    }

    public CompoundTag write(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        openEnds.forEach((face, outlet) -> tag.put(face.getName(), outlet.serializeNBT(registries)));
        return tag;
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries, BlockPos pos) {
        openEnds.clear();
        for (Direction face : Direction.values()) {
            if (tag.contains(face.getName())) {
                openEnds.put(face, OpenEndedPipe.fromNBT(tag.getCompound(face.getName()), registries, pos));
            }
        }
    }

    public void clear() {
        openEnds.clear();
    }
}
