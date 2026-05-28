package com.shellforge.block.entity;

import com.shellforge.block.StressInterfaceBlock;
import com.shellforge.block.entity.ShellBlockBlockEntity.PerFaceStressHub;
import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.ShellDebugSettings;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Sublevel stress endpoint linked to a shell face via Create custom connections,
 * matching Simulated {@code SwivelBearingPlateBlockEntity} propagation semantics.
 */
public class StressInterfaceBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor {
    private @Nullable BlockPos shellLinkPos;
    private @Nullable Direction shellLinkFace;
    private @Nullable UUID shellInteriorSubLevelId;
    private @Nullable UUID shellContainingSubLevelId;

    public StressInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public StressInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.STRESS_INTERFACE.get(), pos, state);
    }

    public void setShellLink(BlockPos shellPos, Direction shellFace, UUID shellInteriorSubLevelId) {
        this.shellLinkPos = shellPos.immutable();
        this.shellLinkFace = shellFace;
        this.shellInteriorSubLevelId = shellInteriorSubLevelId;
        if (level instanceof ServerLevel serverLevel) {
            SubLevel containing = Sable.HELPER.getContaining(serverLevel, shellPos);
            shellContainingSubLevelId = containing != null ? containing.getUniqueId() : null;
        }
        setChanged();
    }

    public boolean isLinkedTo(BlockPos shellPos, Direction shellFace) {
        return shellLinkPos != null
                && shellLinkFace == shellFace
                && shellLinkPos.equals(shellPos);
    }

    public @Nullable BlockPos getShellLinkPos() {
        return shellLinkPos;
    }

    public @Nullable Direction getShellLinkFace() {
        return shellLinkFace;
    }

    public @Nullable PerFaceStressHub resolveLinkedHub() {
        if (level == null || shellLinkPos == null || shellLinkFace == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(new FaceExtraBlockPos(shellLinkPos, shellLinkFace));
        if (blockEntity instanceof PerFaceStressHub hub) {
            return hub;
        }
        return null;
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 0;
        return 0;
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        if (!(other instanceof PerFaceStressHub hub)) {
            return false;
        }
        return shellLinkPos != null
                && shellLinkFace != null
                && hub.getParentBlockEntity().getBlockPos().equals(shellLinkPos)
                && hub.getFace() == shellLinkFace;
    }

    @Override
    public float propagateRotationTo(
            KineticBlockEntity target,
            BlockState stateFrom,
            BlockState stateTo,
            BlockPos diff,
            boolean connectedViaAxes,
            boolean connectedViaCogs
    ) {
        if (target instanceof PerFaceStressHub hub
                && shellLinkPos != null
                && shellLinkFace != null
                && hub.getParentBlockEntity().getBlockPos().equals(shellLinkPos)
                && hub.getFace() == shellLinkFace) {
            return 1;
        }
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        if (shellLinkPos != null && shellLinkFace != null) {
            neighbours.add(new FaceExtraBlockPos(shellLinkPos, shellLinkFace));
        }
        return super.addPropagationLocations(block, state, neighbours);
    }

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        if (ShellDebugSettings.isEnabled()) {
            return null;
        }
        if (level == null || !(level instanceof ServerLevel serverLevel) || shellContainingSubLevelId == null) {
            return null;
        }
        ServerSubLevel containingSubLevel = com.shellforge.shell.ShellManager.getSubLevel(
                serverLevel, shellContainingSubLevelId);
        if (containingSubLevel == null) {
            return null;
        }
        return List.of(containingSubLevel);
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        if (shellLinkPos != null) {
            compound.put("ShellLinkPos", NbtUtils.writeBlockPos(shellLinkPos));
        }
        if (shellLinkFace != null) {
            compound.putString("ShellLinkFace", shellLinkFace.getName());
        }
        if (shellInteriorSubLevelId != null) {
            compound.putUUID("ShellInteriorSubLevelId", shellInteriorSubLevelId);
        }
        if (shellContainingSubLevelId != null) {
            compound.putUUID("ShellContainingSubLevelId", shellContainingSubLevelId);
        }
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        if (compound.contains("ShellLinkPos")) {
            shellLinkPos = NbtUtils.readBlockPos(compound, "ShellLinkPos").orElse(null);
        } else {
            shellLinkPos = null;
        }
        shellLinkFace = compound.contains("ShellLinkFace")
                ? Direction.byName(compound.getString("ShellLinkFace"))
                : null;
        shellInteriorSubLevelId = compound.contains("ShellInteriorSubLevelId")
                ? compound.getUUID("ShellInteriorSubLevelId")
                : compound.contains("ShellSubLevelId") ? compound.getUUID("ShellSubLevelId") : null;
        shellContainingSubLevelId = compound.contains("ShellContainingSubLevelId")
                ? compound.getUUID("ShellContainingSubLevelId")
                : null;
    }
}
