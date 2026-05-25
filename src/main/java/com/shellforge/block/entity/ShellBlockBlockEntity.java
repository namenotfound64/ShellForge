package com.shellforge.block.entity;

import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IExtraFaceKinetics;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.CombinedFluidHandler;
import com.shellforge.shell.ShellBinding;
import com.shellforge.shell.ShellEndpointType;
import com.shellforge.shell.ShellInstance;
import com.shellforge.shell.ShellManager;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shell block entity in the main world.
 * <ul>
 *   <li><b>Passthrough parent</b> — participates in Create as {@link KineticBlockEntity} with zero stress;
 *       does not expose external neighbours ({@link com.shellforge.mixin.RotationPropagatorMixin}).</li>
 *   <li><b>Per-face hubs</b> — {@link PerFaceStressHub} at {@link FaceExtraBlockPos} bridge stress to sublevel
 *       {@link StressInterfaceBlockEntity}; direction uses Create {@code hasSource()} / {@code isSource()}, not manual modes.</li>
 *   <li><b>Redstone / fluid</b> — tick-driven bridging via {@link ShellBinding} list.</li>
 * </ul>
 * See {@code memory/stress_transmission_system.md} for unit conventions (SU vs per-RPM coefficients).
 */
public class ShellBlockBlockEntity extends KineticBlockEntity implements IExtraFaceKinetics {
    private static final float EPSILON = 1e-4f;

    private @Nullable ShellInstance instance;
    private final List<ShellBinding> bindings = new ArrayList<>();
    private final Map<Direction, List<ShellBinding>> bindingsByFace = new EnumMap<>(Direction.class);
    private final Map<Direction, Integer> redstoneOutputs = new EnumMap<>(Direction.class);
    private final Map<Direction, PerFaceStressHub> stressHubs = new EnumMap<>(Direction.class);

    public ShellBlockBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.SHELL_BLOCK.get(), pos, state);
    }

    // ========== Tick ==========

    public static void tick(Level level, BlockPos pos, BlockState state, ShellBlockBlockEntity blockEntity) {
        if (!level.isClientSide) {
            blockEntity.tickStressHubs();
            blockEntity.bridgeRedstone();
        }
        blockEntity.tick();
    }

    private void tickStressHubs() {
        for (Direction face : Direction.values()) {
            if (hasStressFace(face)) {
                getStressHub(face);
            }
        }
        for (PerFaceStressHub hub : stressHubs.values()) {
            hub.syncLevel();
            hub.ensureAttached();
            hub.stressTick();
            hub.tick();
        }
    }

    /** Used by {@link com.shellforge.mixin.LevelMixin} for {@link FaceExtraBlockPos} lookups. */
    @Override
    public @Nullable KineticBlockEntity getFaceKinetics(Direction face) {
        return getStressHub(face);
    }

    public @Nullable KineticBlockEntity getStressHub(Direction face) {
        if (!hasStressFace(face))
            return null;
        PerFaceStressHub hub = stressHubs.computeIfAbsent(face, f -> new PerFaceStressHub(
                SFBlockEntities.SHELL_BLOCK.get(), worldPosition, getBlockState(), this, f));
        hub.syncLevel();
        return hub;
    }

    // ========== Passthrough ==========

    @Override
    public float calculateStressApplied() {
        return 0;
    }

    /** Split-shaft modifier for parent shell: 1 on faces with a stress binding, 0 otherwise. */
    public float getRotationSpeedModifier(Direction face) {
        return hasStressFace(face) ? 1 : 0;
    }

    // ========== Instance management ==========

    public void setInstance(ShellInstance instance) {
        this.instance = instance;
        rebuildBindingIndex(instance.bindings());
        setChanged();
        if (level != null && !level.isClientSide) {
            detachKinetics();
            attachKinetics();
            // Force adjacent kinetic blocks to re-propagate so they discover the new hubs.
            // The shell parent itself has no external kinetic connections (its
            // getPotentialNeighbourLocations is blocked to an empty list), so motors and
            // consumers that were already placed before setInstance was called would not
            // otherwise learn about the newly available PerFaceStressHub instances.
            refreshStressFaceKinetics();
        }
    }

    private void refreshStressFaceKinetics() {
        if (level == null || level.isClientSide) return;
        for (Direction dir : Direction.values()) {
            if (!hasStressFace(dir)) continue;
            PerFaceStressHub hub = (PerFaceStressHub) getStressHub(dir);
            if (hub == null) continue;
            hub.ensureAttached();
            BlockEntity adj = level.getBlockEntity(worldPosition.relative(dir));
            if (adj instanceof KineticBlockEntity kbe) {
                kbe.detachKinetics();
                kbe.attachKinetics();
            }
        }
    }

    public @Nullable ShellInstance getInstance() {
        return instance;
    }

    public void clearInstance() {
        instance = null;
        bindings.clear();
        bindingsByFace.clear();
        redstoneOutputs.clear();
        for (PerFaceStressHub hub : stressHubs.values()) {
            hub.resetState();
        }
        stressHubs.clear();
    }

    public List<ShellBinding> getBindings() {
        return List.copyOf(bindings);
    }

    public List<ShellBinding> getBindings(Direction face) {
        return bindingsByFace.getOrDefault(face, List.of());
    }

    public boolean hasStressFace(Direction face) {
        return bindingsByFace.getOrDefault(face, List.of()).stream()
                .anyMatch(binding -> binding.type() == ShellEndpointType.STRESS);
    }

    public int getRedstoneOutput(Direction shellFace) {
        return redstoneOutputs.getOrDefault(shellFace, 0);
    }

    public void onNeighborChanged(BlockPos fromPos) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel)) return;
        for (Direction face : Direction.values()) {
            if (hasRedstoneOnFace(face)) {
                bridgeRedstoneFace(face);
            }
        }
    }

    // ========== Redstone bridging ==========

    private void bridgeRedstone() {
        if (!(level instanceof ServerLevel serverLevel) || instance == null) return;
        if (ShellManager.getSubLevel(serverLevel, instance.subLevelId()) == null) return;

        for (ShellBinding binding : bindings) {
            if (binding.type() == ShellEndpointType.REDSTONE) {
                bridgeRedstoneBinding(serverLevel, binding);
            }
        }

        for (Direction face : Direction.values()) {
            if (hasRedstoneOnFace(face)) {
                aggregateRedstoneOutput(serverLevel, face);
            }
        }
    }

    private boolean hasRedstoneOnFace(Direction face) {
        return bindingsByFace.getOrDefault(face, List.of()).stream()
                .anyMatch(binding -> binding.type() == ShellEndpointType.REDSTONE);
    }

    private int readExternalRedstoneOnFace(ServerLevel serverLevel, Direction shellFace) {
        if (!hasRedstoneOnFace(shellFace)) return 0;
        BlockPos neighborPos = worldPosition.relative(shellFace);
        return Math.max(
                serverLevel.getSignal(neighborPos, shellFace),
                serverLevel.getDirectSignal(neighborPos, shellFace)
        );
    }

    private void bridgeRedstoneFace(Direction shellFace) {
        if (!(level instanceof ServerLevel serverLevel) || instance == null) return;
        for (ShellBinding binding : bindingsByFace.getOrDefault(shellFace, List.of())) {
            if (binding.type() == ShellEndpointType.REDSTONE) {
                bridgeRedstoneBinding(serverLevel, binding);
            }
        }
        aggregateRedstoneOutput(serverLevel, shellFace);
    }

    private void bridgeRedstoneBinding(ServerLevel serverLevel, ShellBinding binding) {
        BlockEntity internal = getInternalBlockEntity(serverLevel, binding);
        if (!(internal instanceof RedstoneInterfaceBlockEntity redstoneInterface)) return;
        int externalPower = readExternalRedstoneOnFace(serverLevel, binding.shellFace());
        redstoneInterface.applyExternalPower(externalPower, binding.shellFace());
    }

    private void aggregateRedstoneOutput(ServerLevel serverLevel, Direction shellFace) {
        int remotePower = 0;
        for (ShellBinding binding : bindingsByFace.getOrDefault(shellFace, List.of())) {
            if (binding.type() != ShellEndpointType.REDSTONE) continue;
            BlockEntity internal = getInternalBlockEntity(serverLevel, binding);
            if (internal instanceof RedstoneInterfaceBlockEntity redstoneInterface) {
                int signal = redstoneInterface.getInternalOutputPower(serverLevel);
                if (signal > remotePower) remotePower = signal;
            }
        }
        if (remotePower != redstoneOutputs.getOrDefault(shellFace, 0)) {
            redstoneOutputs.put(shellFace, remotePower);
            serverLevel.updateNeighborsAt(worldPosition, getBlockState().getBlock());
            serverLevel.updateNeighborsAt(worldPosition.relative(shellFace), getBlockState().getBlock());
        }
    }

    // ========== Fluid ==========

    public @Nullable IFluidHandler getFluidHandler(@Nullable Direction side) {
        if (!(level instanceof ServerLevel serverLevel) || instance == null || side == null) return null;
        List<IFluidHandler> handlers = new ArrayList<>();
        for (ShellBinding binding : bindingsByFace.getOrDefault(side, List.of())) {
            if (binding.type() != ShellEndpointType.FLUID) continue;
            BlockPos internalPos = resolveBindingPos(serverLevel, binding);
            IFluidHandler handler = serverLevel.getCapability(
                    Capabilities.FluidHandler.BLOCK,
                    internalPos,
                    side.getOpposite()
            );
            if (handler != null) handlers.add(handler);
        }
        return CombinedFluidHandler.merge(handlers);
    }

    // ========== Helpers ==========

    /** Block entity inside the bound sublevel at {@link ShellBinding#internalInterfacePos()}. */
    private @Nullable BlockEntity getInternalBlockEntity(ServerLevel serverLevel, ShellBinding binding) {
        if (instance == null) return null;
        return ShellManager.getInternalBlockEntity(serverLevel, instance.subLevelId(), binding.internalInterfacePos());
    }

    private BlockPos resolveBindingPos(ServerLevel serverLevel, ShellBinding binding) {
        if (instance == null) return BlockPos.ZERO;
        return ShellManager.resolveInternalGlobalPos(serverLevel, instance.subLevelId(), binding.internalInterfacePos());
    }

    private void rebuildBindingIndex(List<ShellBinding> newBindings) {
        bindings.clear();
        bindings.addAll(newBindings);
        bindingsByFace.clear();
        for (ShellBinding binding : newBindings) {
            bindingsByFace.computeIfAbsent(binding.shellFace(), face -> new ArrayList<>()).add(binding);
        }
    }

    // ========== Serialization ==========

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        if (instance != null) {
            compound.putUUID("shellId", instance.shellId());
            compound.putUUID("definitionId", instance.definitionId());
            compound.putUUID("subLevelId", instance.subLevelId());
            compound.putLong("origin", instance.origin().asLong());
            compound.putLong("structureWorldOrigin", instance.structureWorldOrigin().asLong());
            ListTag bindingTags = new ListTag();
            instance.bindings().forEach(binding -> bindingTags.add(binding.save()));
            compound.put("bindings", bindingTags);
        }
        ListTag outputs = new ListTag();
        redstoneOutputs.forEach((face, power) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("face", face.getName());
            tag.putInt("power", power);
            outputs.add(tag);
        });
        compound.put("RedstoneOutputs", outputs);
    }

    private BlockPos resolveStructureWorldOrigin(CompoundTag compound, UUID subLevelId) {
        if (level instanceof ServerLevel serverLevel) {
            var subLevel = ShellManager.getSubLevel(serverLevel, subLevelId);
            if (subLevel != null) {
                return subLevel.getPlot().getCenterBlock();
            }
        }
        if (compound.contains("structureWorldOrigin")) {
            return BlockPos.of(compound.getLong("structureWorldOrigin"));
        }
        return BlockPos.ZERO;
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        stressHubs.clear();
        if (compound.hasUUID("shellId")) {
            List<ShellBinding> loadedBindings = new ArrayList<>();
            ListTag bindingTags = compound.getList("bindings", Tag.TAG_COMPOUND);
            for (int i = 0; i < bindingTags.size(); i++) {
                loadedBindings.add(ShellBinding.load(bindingTags.getCompound(i)));
            }
            UUID subLevelId = compound.getUUID("subLevelId");
            BlockPos structureWorldOrigin = resolveStructureWorldOrigin(compound, subLevelId);
            instance = new ShellInstance(
                    compound.getUUID("shellId"),
                    compound.hasUUID("definitionId") ? compound.getUUID("definitionId") : new UUID(0L, 0L),
                    subLevelId,
                    BlockPos.of(compound.getLong("origin")),
                    structureWorldOrigin,
                    List.copyOf(loadedBindings)
            );
            rebuildBindingIndex(loadedBindings);
        } else {
            instance = null;
            bindings.clear();
            bindingsByFace.clear();
        }
        redstoneOutputs.clear();
        ListTag outputs = compound.getList("RedstoneOutputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < outputs.size(); i++) {
            CompoundTag tag = outputs.getCompound(i);
            redstoneOutputs.put(Direction.byName(tag.getString("face")), tag.getInt("power"));
        }
    }

    /**
     * Per-face Create hub at {@link FaceExtraBlockPos}. One hub per stress {@link ShellBinding#shellFace()}.
     * Direction uses Create {@link #hasSource()} — external drives sublevel, otherwise sublevel drives external.
     */
    public static class PerFaceStressHub extends GeneratingKineticBlockEntity {

        private final ShellBlockBlockEntity parent;
        private final Direction face;

        private float bridgedSpeed;
        private float bridgedStress;
        private float bridgedCapacity;

        public PerFaceStressHub(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                ShellBlockBlockEntity parent, Direction face) {
            super(type, new FaceExtraBlockPos(pos, face), state);
            this.parent = parent;
            this.face = face;
        }

        public Direction getFace() {
            return face;
        }

        void stressTick() {
            syncLevel();
            if (level == null || level.isClientSide) return;
            if (parent.instance == null) return;
            if (!(level instanceof ServerLevel serverLevel)) return;
            if (ShellManager.getSubLevel(serverLevel, parent.instance.subLevelId()) == null) return;

            StressInterfaceBlockEntity si = findStressInterface(serverLevel);
            if (si == null) {
                resetState();
                return;
            }

            if (hasSource()) {
                bridgeExternalToInternal(si);
            } else {
                bridgeInternalToExternal(si);
            }
        }

        private @Nullable StressInterfaceBlockEntity findStressInterface(ServerLevel serverLevel) {
            for (ShellBinding binding : parent.getBindings(face)) {
                if (binding.type() != ShellEndpointType.STRESS) continue;
                BlockEntity internal = parent.getInternalBlockEntity(serverLevel, binding);
                if (internal instanceof StressInterfaceBlockEntity si) return si;
            }
            return null;
        }

        private void bridgeExternalToInternal(StressInterfaceBlockEntity si) {
            float externalSpeed = getSpeed();
            float speedAbs = Math.abs(externalSpeed);

            if (speedAbs < EPSILON) {
                si.setExternalSpeed(0, 0);
                bridgedStress = 0;
                bridgedSpeed = 0;
                bridgedCapacity = 0;
                if (hasNetwork())
                    getOrCreateNetwork().updateStressFor(this, 0);
                return;
            }

            float availableCapacity = Math.max(0, capacity - stress + bridgedStress);
            float capacityPerRpm = speedAbs > EPSILON ? availableCapacity / speedAbs : 0;
            si.setExternalSpeed(externalSpeed, capacityPerRpm);

            float netStress = 0;
            if (si.hasNetwork()) {
                for (float s : si.getOrCreateNetwork().members.values())
                    netStress += s;
            }

            float oldStress = bridgedStress;
            bridgedStress = netStress;
            bridgedSpeed = 0;
            bridgedCapacity = 0;

            if (Math.abs(oldStress - bridgedStress) > EPSILON && hasNetwork()) {
                getOrCreateNetwork().updateStressFor(this, bridgedStress);
            }
        }

        private void bridgeInternalToExternal(StressInterfaceBlockEntity si) {
            si.setExternalSpeed(0, 0);

            float internalSpeed = si.getSpeed();
            float speedAbs = Math.abs(internalSpeed);

            if (speedAbs < EPSILON) {
                if (Math.abs(bridgedSpeed) > EPSILON) {
                    bridgedSpeed = 0;
                    bridgedCapacity = 0;
                    bridgedStress = 0;
                    if (hasNetwork())
                        getOrCreateNetwork().remove(this);
                    detachKinetics();
                    removeSource();
                    reActivateSource = true;
                    setChanged();
                }
                return;
            }

            float netStress = 0;
            float netCapacity = 0;
            if (si.hasNetwork()) {
                for (float s : si.getOrCreateNetwork().members.values())
                    netStress += s;
                for (float c : si.getOrCreateNetwork().sources.values())
                    netCapacity += c;
            }
            float remaining = netCapacity - netStress;
            float newCapacity = Math.max(0, remaining);

            float oldCapacity = bridgedCapacity;
            float oldSpeed = bridgedSpeed;
            bridgedSpeed = internalSpeed;
            bridgedCapacity = newCapacity;
            bridgedStress = 0;

            if (Math.abs(oldSpeed - bridgedSpeed) > EPSILON) {
                if (hasNetwork())
                    getOrCreateNetwork().remove(this);
                detachKinetics();
                removeSource();
                reActivateSource = true;
            } else if (Math.abs(oldCapacity - bridgedCapacity) > EPSILON) {
                if (hasNetwork())
                    getOrCreateNetwork().updateCapacityFor(this, bridgedCapacity);
            }
            setChanged();
        }

        void resetState() {
            bridgedSpeed = 0;
            bridgedStress = 0;
            bridgedCapacity = 0;
            if (hasNetwork())
                getOrCreateNetwork().remove(this);
            detachKinetics();
            removeSource();
        }

        void syncLevel() {
            if (parent.level != null && level != parent.level) {
                setLevel(parent.level);
                clearRemoved();
            }
        }

        public KineticBlockEntity getParentBlockEntity() {
            return parent;
        }

        void ensureAttached() {
            syncLevel();
            if (level == null || level.isClientSide) return;
            if (!hasNetwork()) {
                attachKinetics();
            }
        }

        @Override
        public void onSpeedChanged(float previousSpeed) {
            boolean fromOrToZero = (previousSpeed == 0) != (getSpeed() == 0);
            boolean directionSwap = !fromOrToZero
                    && Math.signum(previousSpeed) != Math.signum(getSpeed());
            if (directionSwap && hasSource()) {
                setChanged();
                return;
            }
            super.onSpeedChanged(previousSpeed);
        }

        @Override
        public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
            return other != null && other.getBlockPos().equals(parent.getBlockPos().relative(face));
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
            if (target != null && target.getBlockPos().equals(parent.getBlockPos().relative(face))) {
                return 1;
            }
            return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
        }

        @Override
        public float getGeneratedSpeed() {
            return hasSource() ? 0 : bridgedSpeed;
        }

        @Override
        public float calculateAddedStressCapacity() {
            if (hasSource() || Math.abs(bridgedSpeed) < EPSILON) {
                lastCapacityProvided = 0;
                return 0;
            }
            lastCapacityProvided = bridgedCapacity;
            return bridgedCapacity;
        }

        @Override
        public float calculateStressApplied() {
            if (!hasSource()) {
                lastStressApplied = 0;
                return 0;
            }
            lastStressApplied = bridgedStress;
            return bridgedStress;
        }
    }
}
