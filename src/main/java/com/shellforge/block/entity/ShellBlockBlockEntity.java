package com.shellforge.block.entity;

import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IExtraFaceKinetics;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.CombinedFluidHandler;
import com.shellforge.shell.ShellBinding;
import com.shellforge.shell.ShellEndpointType;
import com.shellforge.shell.ShellInstance;
import com.shellforge.shell.ShellKineticAngleSync;
import com.shellforge.shell.ShellManager;
import com.shellforge.shell.ShellWirelessRedstoneBridge;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

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
public class ShellBlockBlockEntity extends KineticBlockEntity implements IExtraFaceKinetics, BlockEntitySubLevelActor {
    private static final float EPSILON = 1e-4f;
    private static final Set<ShellBlockBlockEntity> ACTIVE_SHELLS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private @Nullable ShellInstance instance;
    private final ShellWirelessRedstoneBridge wirelessRedstoneBridge = new ShellWirelessRedstoneBridge();
    private final List<ShellBinding> bindings = new ArrayList<>();
    private final Map<Direction, List<ShellBinding>> bindingsByFace = new EnumMap<>(Direction.class);
    private final Map<Direction, Integer> redstoneOutputs = new EnumMap<>(Direction.class);
    private final Map<Direction, PerFaceStressHub> stressHubs = new EnumMap<>(Direction.class);
    /** Last projected global shell anchor synced to sublevel pose / stress hubs. */
    private @Nullable BlockPos lastSyncedShellPos;
    /** Set on NBT load; cleared after sublevel pose and stress hubs are rebuilt. */
    private boolean pendingAnchorSync;

    public ShellBlockBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.SHELL_BLOCK.get(), pos, state);
    }

    // ========== Tick ==========

    public static void tick(Level level, BlockPos pos, BlockState state, ShellBlockBlockEntity blockEntity) {
        if (!level.isClientSide) {
            blockEntity.tickServerMaintenance();
        }
        blockEntity.tick();
    }

    /** Server-side stress bridging, anchor sync, and redstone relay. */
    public void tickServerMaintenance() {
        if (level == null || level.isClientSide) {
            return;
        }
        tickStressHubs();
        bridgeRedstone();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        tickServerMaintenance();
    }

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        if (instance == null || level == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        ServerSubLevel internal = ShellManager.resolveSubLevel(
                serverLevel, instance.subLevelId(), worldPosition);
        if (internal == null) {
            return null;
        }
        return List.of(internal);
    }

    private void tickStressHubs() {
        if (level instanceof ServerLevel serverLevel) {
            syncShellAnchor(serverLevel);
        }
        for (Direction face : Direction.values()) {
            if (hasStressFace(face)) {
                getStressHub(face);
            }
        }
        for (PerFaceStressHub hub : stressHubs.values()) {
            hub.syncLevel();
            hub.ensureAttached();
        }
        if (level instanceof ServerLevel serverLevel) {
            for (PerFaceStressHub hub : stressHubs.values()) {
                hub.stressTickImport(serverLevel);
            }
            for (PerFaceStressHub hub : stressHubs.values()) {
                hub.stressTickExport(serverLevel);
            }
        }
        for (PerFaceStressHub hub : stressHubs.values()) {
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

    public static Set<ShellBlockBlockEntity> getActiveShells() {
        return ACTIVE_SHELLS;
    }

    public void setInstance(ShellInstance instance) {
        this.instance = instance;
        if (level instanceof ServerLevel serverLevel) {
            lastSyncedShellPos = ShellManager.resolveShellAnchorGlobalPos(
                    serverLevel, instance.origin()).immutable();
        } else {
            lastSyncedShellPos = instance.origin().immutable();
        }
        pendingAnchorSync = false;
        rebuildBindingIndex(instance.bindings());
        ACTIVE_SHELLS.add(this);
        if (level instanceof ServerLevel serverLevel) {
            ServerSubLevel subLevel = ShellManager.getSubLevel(serverLevel, instance.subLevelId());
            if (subLevel != null) {
                wirelessRedstoneBridge.refreshInternalLinks(subLevel);
            }
        }
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
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (Direction dir : Direction.values()) {
            if (!hasStressFace(dir)) {
                continue;
            }
            PerFaceStressHub hub = (PerFaceStressHub) getStressHub(dir);
            if (hub == null) {
                continue;
            }
            hub.ensureAttached();
            refreshExternalKineticNeighbor(serverLevel, worldPosition.relative(dir));
        }
        detachKinetics();
        attachKinetics();
    }

    private void refreshExternalKineticNeighbor(ServerLevel serverLevel, BlockPos neighborPos) {
        SubLevel containing = Sable.HELPER.getContaining(serverLevel, neighborPos);
        Sable.HELPER.runIncludingSubLevels(
                serverLevel,
                neighborPos.getCenter(),
                true,
                containing,
                (SubLevelAccess subLevel, BlockPos pos) -> {
                    BlockEntity blockEntity = resolveBlockEntityForSubLevel(serverLevel, subLevel, pos);
                    if (blockEntity instanceof KineticBlockEntity kinetic) {
                        kinetic.detachKinetics();
                        kinetic.attachKinetics();
                        return true;
                    }
                    return null;
                }
        );
    }

    private static @Nullable BlockEntity resolveBlockEntityForSubLevel(
            ServerLevel serverLevel,
            @Nullable SubLevelAccess subLevel,
            BlockPos pos
    ) {
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            return serverSubLevel.getPlot().getEmbeddedLevelAccessor().getBlockEntity(pos);
        }
        return serverLevel.getBlockEntity(pos);
    }

    /** Request a full pose / hub / kinetic rebind (world load, Sable container ready, etc.). */
    public void requestRebindAfterLoad() {
        pendingAnchorSync = true;
    }

    /**
     * Align sublevel pose, rebuild hubs, and refresh internal/external Create networks.
     *
     * @return {@code false} when the bound sublevel or stress endpoints are not ready yet
     */
    public boolean completeShellRebind(ServerLevel serverLevel) {
        if (instance == null) {
            return true;
        }

        BlockPos current = worldPosition;
        ServerSubLevel subLevel = ShellManager.resolveSubLevel(
                serverLevel, instance.subLevelId(), current);
        if (subLevel == null) {
            return false;
        }

        ShellManager.syncSubLevelPose(serverLevel, instance.subLevelId(), current);

        if (ShellManager.hasStressBindings(instance)
                && !ShellManager.areStressEndpointsReachable(serverLevel, instance)) {
            return false;
        }

        reinitializeStressHubs();
        ShellManager.refreshInternalStressKinetics(serverLevel, instance);
        wirelessRedstoneBridge.refreshInternalLinks(subLevel);

        if (!current.equals(instance.origin())) {
            instance = new ShellInstance(
                    instance.shellId(),
                    instance.definitionId(),
                    instance.subLevelId(),
                    current,
                    instance.structureWorldOrigin(),
                    instance.bindings()
            );
        }

        lastSyncedShellPos = ShellManager.resolveShellAnchorGlobalPos(serverLevel, current).immutable();
        setChanged();
        return true;
    }

    /**
     * When a placed shell moves (Simulated physics assembly, etc.), retarget the bound sublevel pose
     * and rebuild per-face hubs so Create networks stay at the shell's current coordinates.
     */
    private void syncShellAnchor(ServerLevel serverLevel) {
        if (instance == null) {
            lastSyncedShellPos = null;
            pendingAnchorSync = false;
            return;
        }

        BlockPos current = worldPosition;
        BlockPos currentGlobal = ShellManager.resolveShellAnchorGlobalPos(serverLevel, current);
        boolean shellMoved = lastSyncedShellPos != null && !currentGlobal.equals(lastSyncedShellPos);
        boolean subLevelOutOfSync = ShellManager.isSubLevelPoseOutOfSync(
                serverLevel, instance.subLevelId(), current);
        boolean needsSync = pendingAnchorSync || shellMoved || subLevelOutOfSync;

        if (!needsSync) {
            if (lastSyncedShellPos == null) {
                lastSyncedShellPos = currentGlobal.immutable();
            }
            return;
        }

        if (!completeShellRebind(serverLevel)) {
            pendingAnchorSync = true;
            return;
        }

        pendingAnchorSync = false;
        lastSyncedShellPos = currentGlobal.immutable();
    }

    private void reinitializeStressHubs() {
        if (level == null || level.isClientSide) {
            return;
        }
        for (PerFaceStressHub hub : stressHubs.values()) {
            hub.resetState();
        }
        stressHubs.clear();
        refreshStressFaceKinetics();
    }

    /** @return {@code true} while a post-load / relocation rebind is still pending. */
    public boolean isPendingAnchorSync() {
        return pendingAnchorSync;
    }

    public @Nullable ShellInstance getInstance() {
        return instance;
    }

    public void clearInstance() {
        ACTIVE_SHELLS.remove(this);
        wirelessRedstoneBridge.clear();
        lastSyncedShellPos = null;
        pendingAnchorSync = false;
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

    @Nullable
    StressInterfaceBlockEntity findStressInterfaceOnFace(ServerLevel serverLevel, Direction face) {
        for (ShellBinding binding : getBindings(face)) {
            if (binding.type() != ShellEndpointType.STRESS) {
                continue;
            }
            BlockEntity internal = getInternalBlockEntity(serverLevel, binding);
            if (internal instanceof StressInterfaceBlockEntity si) {
                return si;
            }
        }
        return null;
    }

    /** Hubs actively importing into the same sublevel network (for splitting load feedback). */
    int countImportingHubsOnNetwork(ServerLevel serverLevel, KineticNetwork network) {
        int count = 0;
        for (Map.Entry<Direction, PerFaceStressHub> entry : stressHubs.entrySet()) {
            PerFaceStressHub hub = entry.getValue();
            if (!hub.isImporting()) {
                continue;
            }
            StressInterfaceBlockEntity si = findStressInterfaceOnFace(serverLevel, entry.getKey());
            if (si != null && si.hasNetwork() && si.getOrCreateNetwork() == network) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    /** Hubs actively exporting from the same sublevel network (split remaining capacity among them). */
    int countExportingHubsOnNetwork(ServerLevel serverLevel, KineticNetwork network) {
        int count = 0;
        for (Map.Entry<Direction, PerFaceStressHub> entry : stressHubs.entrySet()) {
            PerFaceStressHub hub = entry.getValue();
            if (hub.isImporting()) {
                continue;
            }
            StressInterfaceBlockEntity si = findStressInterfaceOnFace(serverLevel, entry.getKey());
            if (si != null && si.hasNetwork() && si.getOrCreateNetwork() == network
                    && Math.abs(si.getSpeed()) > EPSILON) {
                count++;
            }
        }
        return Math.max(1, count);
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

    /**
     * Relays Create wireless redstone across the shell boundary. Called at end of server tick so
     * merged signal levels win over Create's per-world network pass.
     */
    public void bridgeWirelessRedstone() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel) || instance == null) {
            return;
        }
        if (!wirelessRedstoneBridge.hasLinks()) {
            return;
        }
        wirelessRedstoneBridge.bridge(serverLevel, worldPosition, collectRedstoneInterfacePositions());
    }

    private List<BlockPos> collectRedstoneInterfacePositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (ShellBinding binding : bindings) {
            if (binding.type() == ShellEndpointType.REDSTONE) {
                positions.add(binding.internalInterfacePos());
            }
        }
        return positions;
    }

    // ========== Redstone bridging ==========

    private void bridgeRedstone() {
        if (!(level instanceof ServerLevel serverLevel) || instance == null) return;
        if (ShellManager.getSubLevel(serverLevel, instance.subLevelId()) == null) return;

        // Resolve outbound levels before inbound so we do not read our own output back as input.
        for (Direction face : Direction.values()) {
            if (hasRedstoneOnFace(face)) {
                aggregateRedstoneOutput(serverLevel, face);
            }
        }

        for (ShellBinding binding : bindings) {
            if (binding.type() == ShellEndpointType.REDSTONE) {
                bridgeRedstoneBinding(serverLevel, binding);
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
        int neighborSignal = Math.max(
                serverLevel.getSignal(neighborPos, shellFace),
                serverLevel.getDirectSignal(neighborPos, shellFace)
        );
        int ourOutput = redstoneOutputs.getOrDefault(shellFace, 0);
        // Ignore levels at or below our own outbound signal to break external feedback loops.
        return neighborSignal > ourOutput ? neighborSignal : 0;
    }

    private void bridgeRedstoneFace(Direction shellFace) {
        if (!(level instanceof ServerLevel serverLevel) || instance == null) return;
        aggregateRedstoneOutput(serverLevel, shellFace);
        for (ShellBinding binding : bindingsByFace.getOrDefault(shellFace, List.of())) {
            if (binding.type() == ShellEndpointType.REDSTONE) {
                bridgeRedstoneBinding(serverLevel, binding);
            }
        }
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
        if (clientPacket) {
            ListTag hubAngles = new ListTag();
            for (Map.Entry<Direction, PerFaceStressHub> entry : stressHubs.entrySet()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("face", entry.getKey().getName());
                tag.putFloat("visualAngle", entry.getValue().bridgedVisualAngle);
                hubAngles.add(tag);
            }
            compound.put("StressHubAngles", hubAngles);
        }
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
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide || instance == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos currentGlobal = ShellManager.resolveShellAnchorGlobalPos(serverLevel, worldPosition);
        if (lastSyncedShellPos == null || !currentGlobal.equals(lastSyncedShellPos)) {
            pendingAnchorSync = true;
            return;
        }
        if (ShellManager.isSubLevelPoseOutOfSync(serverLevel, instance.subLevelId(), worldPosition)) {
            pendingAnchorSync = true;
        }
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
            lastSyncedShellPos = null;
            pendingAnchorSync = true;
            ACTIVE_SHELLS.add(this);
        } else {
            lastSyncedShellPos = null;
            pendingAnchorSync = false;
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
        if (clientPacket && compound.contains("StressHubAngles")) {
            ListTag hubAngles = compound.getList("StressHubAngles", Tag.TAG_COMPOUND);
            for (int i = 0; i < hubAngles.size(); i++) {
                CompoundTag tag = hubAngles.getCompound(i);
                Direction face = Direction.byName(tag.getString("face"));
                if (face != null && hasStressFace(face)) {
                    PerFaceStressHub hub = stressHubs.get(face);
                    if (hub == null) {
                        hub = (PerFaceStressHub) getStressHub(face);
                    }
                    if (hub != null) {
                        hub.applySyncedVisualAngle(tag.getFloat("visualAngle"));
                    }
                }
            }
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
        private float bridgedVisualAngle;

        public PerFaceStressHub(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                ShellBlockBlockEntity parent, Direction face) {
            super(type, new FaceExtraBlockPos(pos, face), state);
            this.parent = parent;
            this.face = face;
        }

        public Direction getFace() {
            return face;
        }

        boolean isImporting() {
            return hasSource();
        }

        void stressTickImport(ServerLevel serverLevel) {
            if (parent.instance == null) {
                return;
            }
            if (ShellManager.resolveSubLevel(serverLevel, parent.instance.subLevelId(), parent.worldPosition) == null) {
                return;
            }
            if (!hasSource()) {
                return;
            }

            StressInterfaceBlockEntity si = findStressInterface(serverLevel);
            if (si == null) {
                resetState();
                return;
            }
            bridgeExternalToInternal(serverLevel, si);
        }

        void stressTickExport(ServerLevel serverLevel) {
            if (parent.instance == null) {
                return;
            }
            if (ShellManager.resolveSubLevel(serverLevel, parent.instance.subLevelId(), parent.worldPosition) == null) {
                return;
            }

            StressInterfaceBlockEntity si = findStressInterface(serverLevel);
            if (si == null) {
                resetState();
                return;
            }
            if (hasSource() && !shouldPreferInternalExport(si)) {
                return;
            }
            bridgeInternalToExternal(serverLevel, si);
        }

        /** Internal sublevel is driving; export even if this hub still has an external shaft connection. */
        private boolean shouldPreferInternalExport(StressInterfaceBlockEntity si) {
            if (Math.abs(si.getGeneratedSpeed()) > EPSILON) {
                return false;
            }
            return Math.abs(si.getSpeed()) > EPSILON;
        }

        private @Nullable StressInterfaceBlockEntity findStressInterface(ServerLevel serverLevel) {
            return parent.findStressInterfaceOnFace(serverLevel, face);
        }

        private void bridgeExternalToInternal(ServerLevel serverLevel, StressInterfaceBlockEntity si) {
            float externalSpeed = getSpeed();
            float speedAbs = Math.abs(externalSpeed);

            if (speedAbs < EPSILON) {
                si.setExternalSpeed(0, 0);
                si.setExternalVisualAngle(0);
                clearImportState();
                return;
            }

            float availableCapacity = Math.max(0, capacity - stress + bridgedStress);
            float capacityPerRpm = availableCapacity / speedAbs;
            boolean internalSequence = ShellKineticAngleSync.hasActiveTurnSequence(si);
            boolean speedUnchanged = !ShellKineticAngleSync.rpmChanged(si.getExternalSpeed(), externalSpeed)
                    && Math.abs(si.getExternalCapacity() - capacityPerRpm) < EPSILON;
            if (!internalSequence || !speedUnchanged) {
                si.setExternalSpeed(externalSpeed, capacityPerRpm);
            }
            syncInboundRotation(serverLevel, si, externalSpeed);

            float netStress = 0;
            KineticNetwork internalNetwork = null;
            if (si.hasNetwork()) {
                internalNetwork = si.getOrCreateNetwork();
                for (float s : internalNetwork.members.values()) {
                    netStress += s;
                }
            }

            int importSharers = internalNetwork != null
                    ? parent.countImportingHubsOnNetwork(serverLevel, internalNetwork)
                    : 1;

            float oldStress = bridgedStress;
            bridgedStress = netStress / importSharers;
            bridgedSpeed = 0;
            bridgedCapacity = 0;

            if (Math.abs(oldStress - bridgedStress) > EPSILON && hasNetwork()) {
                getOrCreateNetwork().updateStressFor(this, bridgedStress);
            }
        }

        private void bridgeInternalToExternal(ServerLevel serverLevel, StressInterfaceBlockEntity si) {
            si.setExternalSpeed(0, 0);

            float internalSpeed = si.getSpeed();
            float speedAbs = Math.abs(internalSpeed);

            if (speedAbs < EPSILON) {
                clearExportState();
                bridgedVisualAngle = 0;
                sequenceContext = null;
                return;
            }

            if (!si.hasNetwork()) {
                clearExportState();
                bridgedVisualAngle = 0;
                sequenceContext = null;
                return;
            }

            KineticNetwork internalNetwork = si.getOrCreateNetwork();

            float netStress = 0;
            float netCapacity = 0;
            for (float s : internalNetwork.members.values()) {
                netStress += s;
            }
            for (float c : internalNetwork.sources.values()) {
                netCapacity += c;
            }
            float remaining = netCapacity - netStress;
            int exportSharers = parent.countExportingHubsOnNetwork(serverLevel, internalNetwork);
            float newCapacity = Math.max(0, remaining / exportSharers);

            ShellKineticAngleSync.SpringSequenceState springSequence =
                    ShellKineticAngleSync.findActiveSpringSequence(si);

            float oldCapacity = bridgedCapacity;
            float oldSpeed = bridgedSpeed;
            float exportSpeed = springSequence != null ? springSequence.rpm() : internalSpeed;
            bridgedSpeed = exportSpeed;
            bridgedCapacity = newCapacity;
            bridgedStress = 0;
            syncOutboundRotation(serverLevel, si, springSequence);

            boolean springTurn = springSequence != null;
            if (ShellKineticAngleSync.rpmChanged(oldSpeed, bridgedSpeed)) {
                if (springTurn) {
                    reActivateSource = true;
                    if (hasNetwork()) {
                        getOrCreateNetwork().updateCapacityFor(this, bridgedCapacity);
                    }
                } else {
                    boolean fromOrToZero = (Math.abs(oldSpeed) < EPSILON) != (Math.abs(bridgedSpeed) < EPSILON);
                    boolean signFlip = Math.abs(oldSpeed) > EPSILON && Math.abs(bridgedSpeed) > EPSILON
                            && Math.signum(oldSpeed) != Math.signum(bridgedSpeed);
                    if (fromOrToZero || signFlip) {
                        if (hasNetwork()) {
                            getOrCreateNetwork().remove(this);
                        }
                        detachKinetics();
                        removeSource();
                        reActivateSource = true;
                    } else {
                        reActivateSource = true;
                        if (hasNetwork()) {
                            getOrCreateNetwork().updateCapacityFor(this, bridgedCapacity);
                        }
                    }
                }
            } else if (Math.abs(oldCapacity - bridgedCapacity) > EPSILON) {
                if (hasNetwork()) {
                    getOrCreateNetwork().updateCapacityFor(this, bridgedCapacity);
                }
            }
            setChanged();
        }

        private void syncInboundRotation(ServerLevel serverLevel, StressInterfaceBlockEntity si, float speed) {
            // Do not overwrite internal spring/sequencer context with a null external hub context.
            if (ShellKineticAngleSync.hasActiveTurnSequence(si)) {
                return;
            }
            ShellKineticAngleSync.copySequenceContextIfPresent(this, si);
            bridgedVisualAngle = 0;
            si.setExternalVisualAngle(0);
            parent.setChanged();
            parent.sendData();
            si.sendData();
        }

        private void syncOutboundRotation(
                ServerLevel serverLevel,
                StressInterfaceBlockEntity si,
                @Nullable ShellKineticAngleSync.SpringSequenceState springSequence
        ) {
            SequencedGearshiftBlockEntity.SequenceContext incoming = springSequence != null
                    ? springSequence.context()
                    : ShellKineticAngleSync.findSequenceContextInChain(si);
            boolean contextChanged = ShellKineticAngleSync.sequenceContextChanged(this.sequenceContext, incoming);
            this.sequenceContext = incoming;
            this.bridgedVisualAngle = 0;
            if (contextChanged) {
                if (incoming != null) {
                    ShellKineticAngleSync.refreshDownstreamSequenceContext(this);
                } else {
                    ShellKineticAngleSync.clearDownstreamSequenceContext(this);
                }
            }
            parent.setChanged();
            parent.sendData();
        }

        void applySyncedVisualAngle(float visualAngle) {
            bridgedVisualAngle = visualAngle;
        }

        public float getBridgedVisualAngle() {
            return bridgedVisualAngle;
        }

        private void clearImportState() {
            float oldStress = bridgedStress;
            bridgedStress = 0;
            bridgedSpeed = 0;
            bridgedCapacity = 0;
            if (Math.abs(oldStress) > EPSILON && hasNetwork()) {
                getOrCreateNetwork().updateStressFor(this, 0);
            }
        }

        private void clearExportState() {
            if (Math.abs(bridgedSpeed) > EPSILON || Math.abs(bridgedCapacity) > EPSILON) {
                boolean hadSequence = sequenceContext != null;
                bridgedSpeed = 0;
                bridgedCapacity = 0;
                bridgedStress = 0;
                bridgedVisualAngle = 0;
                sequenceContext = null;
                if (hadSequence) {
                    ShellKineticAngleSync.clearDownstreamSequenceContext(this);
                }
                if (hasNetwork()) {
                    getOrCreateNetwork().remove(this);
                }
                detachKinetics();
                removeSource();
                reActivateSource = true;
                setChanged();
            }
        }

        void resetState() {
            bridgedSpeed = 0;
            bridgedStress = 0;
            bridgedCapacity = 0;
            bridgedVisualAngle = 0;
            sequenceContext = null;
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
        public int getRotationAngleOffset(Direction.Axis axis) {
            if (face.getAxis() != axis || sequenceContext != null) {
                return 0;
            }
            return ShellKineticAngleSync.rotationOffsetForVisualAngle(this, axis, bridgedVisualAngle);
        }

        @Override
        protected void copySequenceContextFrom(KineticBlockEntity sourceBE) {
            SequencedGearshiftBlockEntity.SequenceContext fromSource =
                    ShellKineticAngleSync.probeSequenceContext(sourceBE);
            if (fromSource != null) {
                sequenceContext = fromSource;
                return;
            }
            super.copySequenceContextFrom(sourceBE);
        }

        @Override
        protected boolean syncSequenceContext() {
            return sequenceContext != null;
        }

        @Override
        public float getGeneratedSpeed() {
            return Math.abs(bridgedSpeed) < EPSILON ? 0 : bridgedSpeed;
        }

        @Override
        public float calculateAddedStressCapacity() {
            if (Math.abs(bridgedSpeed) < EPSILON) {
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
