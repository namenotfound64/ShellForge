package com.shellforge.block.entity;

import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IExtraFaceKinetics;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.ShellFaceFluidHandler;
import com.shellforge.shell.ShellFluidPressureBridge;
import com.shellforge.shell.ShellFluidOutlet;
import com.shellforge.shell.ShellFluidRouting;
import com.shellforge.shell.ShellBinding;
import com.shellforge.shell.ShellDebugSettings;
import com.shellforge.shell.ShellEndpointType;
import com.shellforge.shell.ShellInstance;
import com.shellforge.content.ShellKineticLinkPos;
import com.shellforge.content.ShellFluidFacePos;
import com.shellforge.shell.ShellManager;
import com.shellforge.shell.ShellWirelessRedstoneBridge;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate;
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
 *   <li><b>Per-face hubs</b> — {@link PerFaceStressHub} at {@link FaceExtraBlockPos} link to sublevel
 *       {@link StressInterfaceBlockEntity} via Create custom connections (Swivel bearing model).</li>
 *   <li><b>Redstone</b> — tick-driven bridging via {@link ShellBinding} list.</li>
 *   <li><b>Fluid</b> — per-face {@link net.neoforged.neoforge.fluids.capability.IFluidHandler} endpoints with pressure-driven exterior outlets.</li>
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
    private final Map<Direction, ShellFaceFluidHandler> fluidHandlersByFace = new EnumMap<>(Direction.class);
    private final List<ShellFluidPressureBridge.AppliedPressure> appliedFluidPressure = new ArrayList<>();
    private final ShellFluidOutlet fluidOutlet = new ShellFluidOutlet();
    private long lastFluidOutputTick = Long.MIN_VALUE;
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
        bridgeFluidPressure();
        bridgeRedstone();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        tickServerMaintenance();
        bridgeWirelessRedstone(subLevel, "sublevel-tick");
    }

    @Override
    public @Nullable Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        if (ShellDebugSettings.isEnabled()) {
            return null;
        }
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
            linkStressInterfaces(serverLevel);
        }
        for (PerFaceStressHub hub : stressHubs.values()) {
            hub.tick();
        }
    }

    private void linkStressInterfaces(ServerLevel serverLevel) {
        if (instance == null) {
            return;
        }
        for (ShellBinding binding : bindings) {
            if (binding.type() != ShellEndpointType.STRESS) {
                continue;
            }
            BlockEntity internal = ShellManager.getInternalBlockEntity(
                    serverLevel, instance.subLevelId(), binding.internalInterfacePos(), worldPosition);
            if (internal instanceof StressInterfaceBlockEntity stressInterface) {
                stressInterface.setShellLink(worldPosition, binding.shellFace(), instance.subLevelId());
            }
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
            ServerSubLevel subLevel = ShellManager.resolveSubLevel(
                    serverLevel, instance.subLevelId(), worldPosition);
            if (subLevel != null) {
                BlockPos shellAnchor = ShellManager.resolveShellAnchorGlobalPos(serverLevel, worldPosition);
                wirelessRedstoneBridge.refreshInternalLinks(subLevel, shellAnchor, instance.structureSize());
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
            refreshAllFluidFaceNeighbors();
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
        linkStressInterfaces(serverLevel);
        ShellManager.refreshInternalStressKinetics(serverLevel, instance);
        wirelessRedstoneBridge.refreshInternalLinks(
                subLevel, ShellManager.resolveShellAnchorGlobalPos(serverLevel, current), instance.structureSize());

        if (!current.equals(instance.origin())) {
            instance = new ShellInstance(
                    instance.shellId(),
                    instance.definitionId(),
                    instance.subLevelId(),
                    current,
                    instance.structureWorldOrigin(),
                    instance.structureSize(),
                    instance.bindings()
            );
        }

        lastSyncedShellPos = ShellManager.resolveShellAnchorGlobalPos(serverLevel, current).immutable();
        refreshAllFluidFaceNeighbors();
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
        fluidOutlet.clear();
        lastFluidOutputTick = Long.MIN_VALUE;
        ACTIVE_SHELLS.remove(this);
        wirelessRedstoneBridge.clear();
        lastSyncedShellPos = null;
        pendingAnchorSync = false;
        instance = null;
        bindings.clear();
        bindingsByFace.clear();
        fluidHandlersByFace.clear();
        clearAppliedFluidPressure();
        redstoneOutputs.clear();
        for (PerFaceStressHub hub : stressHubs.values()) {
            hub.resetState();
        }
        stressHubs.clear();
        if (level != null && !level.isClientSide) {
            invalidateCapabilities();
        }
    }

    @Override
    public void invalidate() {
        clearAppliedFluidPressure();
        super.invalidate();
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
    public StressInterfaceBlockEntity findStressInterfaceOnFace(ServerLevel serverLevel, Direction face) {
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
     * <p>
     * Uses cached internal link lists when available; does not require a live sublevel resolve each tick.
     */
    public void bridgeWirelessRedstone() {
        bridgeWirelessRedstone(null, "post-tick");
    }

    /**
     * @param subLevelHint when called from {@link #sable$tick}, avoids an extra resolve and refreshes from the ticking plot
     */
    public void bridgeWirelessRedstone(@Nullable ServerSubLevel subLevelHint) {
        bridgeWirelessRedstone(subLevelHint, subLevelHint != null ? "sublevel-tick" : "post-tick");
    }

    private void bridgeWirelessRedstone(@Nullable ServerSubLevel subLevelHint, String source) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel) || instance == null) {
            return;
        }
        ServerSubLevel subLevel = subLevelHint;
        if (subLevel == null || subLevel.isRemoved()) {
            subLevel = ShellManager.resolveSubLevel(
                    serverLevel, instance.subLevelId(), worldPosition);
        }
        BlockPos shellAnchor = ShellManager.resolveShellAnchorGlobalPos(serverLevel, worldPosition);
        boolean subLevelResolved = subLevel != null && !subLevel.isRemoved();
        if (subLevelResolved && !"sublevel-tick".equals(source)) {
            wirelessRedstoneBridge.refreshInternalLinks(subLevel, shellAnchor, instance.structureSize());
        }
        boolean hasLinks = wirelessRedstoneBridge.hasLinks();
        wirelessRedstoneBridge.logEntry(source, serverLevel, shellAnchor, worldPosition, subLevelResolved, hasLinks);
        if (!hasLinks) {
            return;
        }
        wirelessRedstoneBridge.bridge(serverLevel, shellAnchor, instance.subLevelId(), source);
    }

    // ========== Redstone bridging ==========

    private void bridgeRedstone() {
        if (!(level instanceof ServerLevel serverLevel) || instance == null) return;
        if (ShellManager.resolveSubLevel(serverLevel, instance.subLevelId(), worldPosition) == null) return;

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

    /**
     * Stable fluid endpoint for a shell face (client + server). Create {@code FluidTransportBehaviour}
     * caches this handler; actual fill/drain delegates into the sublevel on the server.
     */
    public @Nullable IFluidHandler getFluidHandler(@Nullable Direction side) {
        // Capability context, not a synthetic BlockPos, identifies the independent face.
        return side == null ? null : getFluidFaceHandler(side, side);
    }

    public @Nullable IFluidHandler getFluidFaceHandler(Direction shellFace, @Nullable Direction side) {
        if (isRemoved() || level == null || instance == null || !hasFluidOnFace(shellFace)) {
            return null;
        }
        if (side != null && side != shellFace) {
            return null;
        }
        return fluidHandlersByFace.computeIfAbsent(shellFace, face -> new ShellFaceFluidHandler(this, face));
    }

    public boolean hasFluidOnFace(Direction face) {
        return bindingsByFace.getOrDefault(face, List.of()).stream()
                .anyMatch(binding -> binding.type() == ShellEndpointType.FLUID);
    }

    private void refreshFluidFaceNeighbors(Direction shellFace) {
        if (level == null || level.isClientSide) {
            return;
        }
        invalidateCapabilities();
        level.invalidateCapabilities(new ShellFluidFacePos(worldPosition, shellFace));
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        BlockPos exteriorNeighbor = worldPosition.relative(shellFace);
        if (level.isLoaded(exteriorNeighbor)) {
            BlockState pipeState = level.getBlockState(exteriorNeighbor);
            if (com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, exteriorNeighbor) != null) {
                com.simibubi.create.content.fluids.FluidPropagator.propagateChangedPipe(level, exteriorNeighbor, pipeState);
            }
        }
    }

    public void onInternalFluidInterfaceChanged(ServerLevel serverLevel, FluidInterfaceBlockEntity fluidInterface) {
        ShellInstance currentInstance = instance;
        if (currentInstance == null) {
            return;
        }
        for (ShellBinding binding : bindings) {
            if (binding.type() != ShellEndpointType.FLUID) {
                continue;
            }
            BlockEntity internal = ShellManager.getInternalBlockEntity(
                    serverLevel,
                    currentInstance.subLevelId(),
                    binding.internalInterfacePos(),
                    worldPosition
            );
            if (internal == fluidInterface) {
                refreshFluidFaceNeighbors(binding.shellFace());
            }
        }
    }

    private void bridgeFluidPressure() {
        if (!(level instanceof ServerLevel serverLevel) || instance == null) {
            clearAppliedFluidPressure();
            return;
        }
        clearAppliedFluidPressure();

        boolean outputThisTick = lastFluidOutputTick != serverLevel.getGameTime();
        lastFluidOutputTick = serverLevel.getGameTime();
        List<ShellFluidRouting.Link> fluidLinks = new ArrayList<>();
        Map<Direction, List<IFluidHandler>> outputHandlers = new EnumMap<>(Direction.class);
        for (ShellBinding binding : bindings) {
            if (binding.type() != ShellEndpointType.FLUID) {
                continue;
            }
            BlockEntity internal = ShellManager.getInternalBlockEntity(
                    serverLevel,
                    instance.subLevelId(),
                    binding.internalInterfacePos(),
                    worldPosition
            );
            if (!(internal instanceof FluidInterfaceBlockEntity fluidInterface) || fluidInterface.isRemoved()) {
                continue;
            }

            Direction face = binding.shellFace();
            Level internalLevel = fluidInterface.getLevel();
            if (internalLevel == null) {
                continue;
            }
            IFluidHandler handler = fluidInterface.getFluidHandler(face.getOpposite());
            if (handler == null) continue;
            fluidLinks.add(new ShellFluidRouting.Link(
                    new ShellFluidPressureBridge.Endpoint(serverLevel, worldPosition, face),
                    new ShellFluidPressureBridge.Endpoint(internalLevel, fluidInterface.getBlockPos(), face.getOpposite())));
            outputHandlers.computeIfAbsent(face, ignored -> new ArrayList<>()).add(handler);
        }
        ShellFluidRouting.Result result = ShellFluidRouting.solve(fluidLinks);
        appliedFluidPressure.addAll(result.applied());
        if (outputThisTick) outputHandlers.forEach((face, handlers) -> {
            IFluidHandler source = handlers.size() == 1 ? handlers.getFirst()
                    : new com.simibubi.create.foundation.fluid.CombinedTankWrapper(handlers.toArray(IFluidHandler[]::new));
            float pressure = result.outputPressure().getOrDefault(
                    new ShellFluidPressureBridge.Endpoint(serverLevel, worldPosition, face), 0f);
            fluidOutlet.exchange(this, face, source, pressure);
        });
    }

    private void clearAppliedFluidPressure() {
        if (appliedFluidPressure.isEmpty()) {
            return;
        }
        for (ShellFluidPressureBridge.AppliedPressure pressure : appliedFluidPressure) {
            pressure.remove();
        }
        appliedFluidPressure.clear();
    }

    private static void propagateAdjacentCreatePipes(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighbor);
            if (com.simibubi.create.content.fluids.pipes.FluidPipeBlock.isPipe(neighborState)) {
                com.simibubi.create.content.fluids.FluidPropagator.propagateChangedPipe(level, neighbor, neighborState);
            }
        }
    }

    private void refreshAllFluidFaceNeighbors() {
        if (level == null || level.isClientSide) {
            return;
        }
        for (Direction face : Direction.values()) {
            if (hasFluidOnFace(face)) {
                refreshFluidFaceNeighbors(face);
            }
        }
    }

    // ========== Helpers ==========

    /** Block entity inside the bound sublevel at {@link ShellBinding#internalInterfacePos()}. */
    private @Nullable BlockEntity getInternalBlockEntity(ServerLevel serverLevel, ShellBinding binding) {
        if (instance == null) return null;
        return ShellManager.getInternalBlockEntity(
                serverLevel, instance.subLevelId(), binding.internalInterfacePos(), worldPosition);
    }

    private void rebuildBindingIndex(List<ShellBinding> newBindings) {
        bindings.clear();
        bindings.addAll(newBindings);
        bindingsByFace.clear();
        fluidHandlersByFace.clear();
        for (ShellBinding binding : newBindings) {
            bindingsByFace.computeIfAbsent(binding.shellFace(), face -> new ArrayList<>()).add(binding);
        }
        if (level != null && !level.isClientSide) {
            invalidateCapabilities();
        }
    }

    // ========== Serialization ==========

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.put("FluidOutlets", fluidOutlet.write(registries));
        if (instance != null) {
            compound.putUUID("shellId", instance.shellId());
            compound.putUUID("definitionId", instance.definitionId());
            compound.putUUID("subLevelId", instance.subLevelId());
            compound.putLong("origin", instance.origin().asLong());
            compound.putLong("structureWorldOrigin", instance.structureWorldOrigin().asLong());
            compound.putLong("structureSize", instance.structureSize().asLong());
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

    private static BlockPos resolveStructureSize(CompoundTag compound, List<ShellBinding> bindings) {
        if (compound.contains("structureSize")) {
            return BlockPos.of(compound.getLong("structureSize"));
        }
        BlockPos max = BlockPos.ZERO;
        for (ShellBinding binding : bindings) {
            BlockPos pos = binding.internalInterfacePos();
            max = new BlockPos(
                    Math.max(max.getX(), pos.getX()),
                    Math.max(max.getY(), pos.getY()),
                    Math.max(max.getZ(), pos.getZ())
            );
        }
        // Legacy saves: pad beyond known interface positions so interior links are included.
        return new BlockPos(max.getX() + 16, max.getY() + 16, max.getZ() + 16);
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
        fluidOutlet.read(compound.getCompound("FluidOutlets"), registries, worldPosition);
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
                    resolveStructureSize(compound, loadedBindings),
                    List.copyOf(loadedBindings)
            );
            rebuildBindingIndex(loadedBindings);
            lastSyncedShellPos = null;
            pendingAnchorSync = true;
            ACTIVE_SHELLS.add(this);
            if (level instanceof ServerLevel serverLevel) {
                ServerSubLevel subLevel = ShellManager.getSubLevel(serverLevel, subLevelId);
                if (subLevel != null) {
                    BlockPos shellAnchor = ShellManager.resolveShellAnchorGlobalPos(serverLevel, worldPosition);
                    wirelessRedstoneBridge.refreshInternalLinks(subLevel, shellAnchor, instance.structureSize());
                }
            }
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
    }

    /**
     * Per-face Create hub at {@link FaceExtraBlockPos}. Linked to internal
     * {@link StressInterfaceBlockEntity} via Create custom connections (Swivel bearing model).
     */
    public static class PerFaceStressHub extends KineticBlockEntity {
        private static final float EPSILON = 1e-4f;

        private final ShellBlockBlockEntity parent;
        private final Direction face;

        public PerFaceStressHub(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                ShellBlockBlockEntity parent, Direction face) {
            super(type, new FaceExtraBlockPos(pos, face), state);
            this.parent = parent;
            this.face = face;
        }

        public Direction getFace() {
            return face;
        }

        public ShellBlockBlockEntity getShellParent() {
            return parent;
        }

        /** External shaft is driving into the shell on this face. */
        boolean isImporting() {
            return hasExternalSource();
        }

        boolean hasExternalSource() {
            if (!hasSource() || source == null) {
                return false;
            }
            return source.equals(parent.getBlockPos().relative(face));
        }

        private @Nullable StressInterfaceBlockEntity findStressInterface(ServerLevel serverLevel) {
            return parent.findStressInterfaceOnFace(serverLevel, face);
        }

        private float computeImportStressShare(ServerLevel serverLevel) {
            StressInterfaceBlockEntity si = findStressInterface(serverLevel);
            if (si == null || !si.hasNetwork()) {
                return 0;
            }
            KineticNetwork internalNetwork = si.getOrCreateNetwork();
            float netStress = 0;
            for (float s : internalNetwork.members.values()) {
                netStress += s;
            }
            int importSharers = parent.countImportingHubsOnNetwork(serverLevel, internalNetwork);
            return netStress / importSharers;
        }

        @Override
        public float calculateStressApplied() {
            if (!hasExternalSource() || !(level instanceof ServerLevel serverLevel)) {
                lastStressApplied = 0;
                return 0;
            }
            lastStressApplied = computeImportStressShare(serverLevel);
            return lastStressApplied;
        }

        void resetState() {
            if (hasNetwork()) {
                getOrCreateNetwork().remove(this);
            }
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
            if (level == null || level.isClientSide) {
                return;
            }
            if (!hasNetwork()) {
                attachKinetics();
            }
        }

        @Override
        public void onSpeedChanged(float previousSpeed) {
            boolean fromOrToZero = (previousSpeed == 0) != (getSpeed() == 0);
            boolean directionSwap = !fromOrToZero
                    && Math.signum(previousSpeed) != Math.signum(getSpeed());
            if (directionSwap && hasExternalSource()) {
                setChanged();
                return;
            }
            super.onSpeedChanged(previousSpeed);
        }

        @Override
        public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
            if (other instanceof StressInterfaceBlockEntity si) {
                return si.isLinkedTo(parent.getBlockPos(), face);
            }
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
            if (target instanceof StressInterfaceBlockEntity si
                    && si.isLinkedTo(parent.getBlockPos(), face)) {
                return 1;
            }
            if (target != null && target.getBlockPos().equals(parent.getBlockPos().relative(face))) {
                return 1;
            }
            return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
        }

        @Override
        public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
            neighbours.add(parent.getBlockPos().relative(face));
            neighbours.add(new ShellKineticLinkPos(parent.getBlockPos(), face));
            return super.addPropagationLocations(block, state, neighbours);
        }
    }
}
