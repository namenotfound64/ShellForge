package com.shellforge.shell;

import com.shellforge.block.FaceMarkerBlock;
import com.shellforge.block.FluidInterfaceBlock;
import com.shellforge.block.RedstoneInterfaceBlock;
import com.shellforge.block.StressInterfaceBlock;
import com.shellforge.block.entity.StressInterfaceBlockEntity;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.registry.SFBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creates shell definitions, materializes {@link ServerSubLevel} instances at {@link #REMOTE_Y},
 * and resolves binding positions between main world and sublevels.
 */
public final class ShellManager {
    /** Y level for remote Sable plot placement (see sublevel plot center). */
    private static final int REMOTE_Y = -1024;

    private ShellManager() {
    }

    /** Post-tick shell maintenance (wireless redstone relay runs after Create link networks update). */
    private static final java.util.List<Integer> deferredRebindSchedule = new java.util.ArrayList<>();

    public static void scheduleDeferredRebind(int ticks) {
        if (ticks <= 0) {
            return;
        }
        deferredRebindSchedule.add(ticks);
    }

    public static void schedulePostLoadRebindSequence() {
        scheduleDeferredRebind(20);
        scheduleDeferredRebind(80);
        scheduleDeferredRebind(160);
    }

    private static void requestAllShellRebinds(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ShellBlockBlockEntity shell : new java.util.ArrayList<>(ShellBlockBlockEntity.getActiveShells())) {
                if (shell.getLevel() == level && shell.getInstance() != null) {
                    shell.requestRebindAfterLoad();
                    shell.tickServerMaintenance();
                }
            }
        }
    }

    public static void serverTick(MinecraftServer server) {
        for (int i = deferredRebindSchedule.size() - 1; i >= 0; i--) {
            int remaining = deferredRebindSchedule.get(i) - 1;
            if (remaining <= 0) {
                deferredRebindSchedule.remove(i);
                requestAllShellRebinds(server);
            } else {
                deferredRebindSchedule.set(i, remaining);
            }
        }
        for (ShellBlockBlockEntity shell : ShellBlockBlockEntity.getActiveShells()) {
            if (shell.isPendingAnchorSync()) {
                shell.tickServerMaintenance();
            }
            shell.bridgeWirelessRedstone();
        }
        if (ShellDebugSettings.isEnabled()) {
            maintainDebugProjectedShells(server);
        }
    }

    /** Debug projection is one-shot; keep projected sublevels from simulating without re-teleporting them. */
    private static void maintainDebugProjectedShells(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (!(container instanceof ServerSubLevelContainer serverContainer)) {
                continue;
            }
            for (ShellBlockBlockEntity shell : ShellBlockBlockEntity.getActiveShells()) {
                if (shell.getLevel() != level || shell.getInstance() == null) {
                    continue;
                }
                ServerSubLevel subLevel = resolveSubLevel(
                        level, shell.getInstance().subLevelId(), shell.getBlockPos());
                if (subLevel != null && !subLevel.isRemoved()) {
                    freezeSubLevelPhysics(serverContainer, subLevel);
                }
            }
        }
    }

    /** One-shot debug projection for every active shell in a level. */
    public static void projectAllShellsForDebug(ServerLevel level) {
        for (ShellBlockBlockEntity shell : new java.util.ArrayList<>(ShellBlockBlockEntity.getActiveShells())) {
            if (shell.getLevel() != level || shell.getInstance() == null) {
                continue;
            }
            ServerSubLevel subLevel = resolveSubLevel(
                    level, shell.getInstance().subLevelId(), shell.getBlockPos());
            if (subLevel != null && !subLevel.isRemoved()) {
                projectSubLevelForDebug(level, subLevel, null, shell.getBlockPos());
            }
        }
    }

    /** Return all shell interiors to their normal remote pose after debug is disabled. */
    public static void restoreAllShellPoses(ServerLevel level) {
        for (ShellBlockBlockEntity shell : new java.util.ArrayList<>(ShellBlockBlockEntity.getActiveShells())) {
            if (shell.getLevel() != level || shell.getInstance() == null) {
                continue;
            }
            syncSubLevelPose(level, shell.getInstance().subLevelId(), shell.getBlockPos());
        }
    }

    /** Rebind shells once Sable's sublevel container finishes loading (after world join). */
    public static void onSubLevelContainerReady(Level level, SubLevelContainer container) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        scheduleDeferredRebind(5);
        scheduleDeferredRebind(40);
        for (ShellBlockBlockEntity shell : new java.util.ArrayList<>(ShellBlockBlockEntity.getActiveShells())) {
            if (shell.getLevel() == serverLevel && shell.getInstance() != null) {
                shell.requestRebindAfterLoad();
            }
        }
    }

    public static boolean hasStressBindings(ShellInstance instance) {
        for (ShellBinding binding : instance.bindings()) {
            if (binding.type() == ShellEndpointType.STRESS) {
                return true;
            }
        }
        return false;
    }

    public static boolean areStressEndpointsReachable(ServerLevel level, ShellInstance instance) {
        if (resolveSubLevel(level, instance.subLevelId(), instance.origin()) == null) {
            return false;
        }
        for (ShellBinding binding : instance.bindings()) {
            if (binding.type() != ShellEndpointType.STRESS) {
                continue;
            }
            BlockEntity blockEntity = getInternalBlockEntity(
                    level, instance.subLevelId(), binding.internalInterfacePos(), instance.origin());
            if (!(blockEntity instanceof StressInterfaceBlockEntity)) {
                return false;
            }
        }
        return true;
    }

    public static void refreshInternalStressKinetics(ServerLevel level, ShellInstance instance) {
        if (resolveSubLevel(level, instance.subLevelId(), instance.origin()) == null) {
            return;
        }
        for (ShellBinding binding : instance.bindings()) {
            if (binding.type() != ShellEndpointType.STRESS) {
                continue;
            }
            BlockEntity blockEntity = getInternalBlockEntity(
                    level, instance.subLevelId(), binding.internalInterfacePos(), instance.origin());
            if (blockEntity instanceof StressInterfaceBlockEntity stressInterface) {
                stressInterface.detachKinetics();
                stressInterface.attachKinetics();
            }
        }
    }

    public static ShellDefinition createDefinition(
            ServerLevel level,
            BlockPos pos1,
            BlockPos pos2,
            Map<Direction, List<ShellEndpointType>> faceOverrides
    ) {
        BlockPos min = min(pos1, pos2);
        BlockPos max = max(pos1, pos2);

        List<ShellBlockSnapshot> snapshots = new ArrayList<>();
        for (BlockPos scannedPos : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = scannedPos.immutable();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(SFBlocks.FACE_MARKER.get())) {
                continue;
            }

            CompoundTag blockEntityTag = null;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntityTag = blockEntity.saveWithFullMetadata(level.registryAccess());
            }

            snapshots.add(new ShellBlockSnapshot(pos.subtract(min), state, blockEntityTag));
        }

        BlockPos size = new BlockPos(
                max.getX() - min.getX() + 1,
                max.getY() - min.getY() + 1,
                max.getZ() - min.getZ() + 1
        );

        List<ShellEndpoint> endpoints = discoverEndpoints(level, min, max, faceOverrides);

        return new ShellDefinition(
                UUID.randomUUID(),
                min,
                size,
                List.copyOf(snapshots),
                endpoints
        );
    }

    public static ServerSubLevel packSourceToTemplateSubLevel(ServerLevel level, BlockPos pos1, BlockPos pos2) {
        BlockPos min = min(pos1, pos2);
        BlockPos max = max(pos1, pos2);
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos scannedPos : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = scannedPos.immutable();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(SFBlocks.FACE_MARKER.get())) {
                continue;
            }
            blocks.add(pos);
        }

        if (blocks.isEmpty()) {
            throw new ShellCopyException("Cannot pack an empty shell selection");
        }

        BoundingBox3i bounds = new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, min, blocks, bounds);
        subLevel.setName("ShellForge Shell Template");
        moveSubLevelFarBelow(subLevel, min);
        return subLevel;
    }

    public static ShellInstance instantiate(ServerLevel level, ShellDefinition definition, BlockPos shellOrigin) {
        ServerSubLevel subLevel = createSubLevel(level, shellOrigin);
        materializeBlocks(level, subLevel, definition);
        if (ShellDebugSettings.isEnabled()) {
            projectSubLevelForDebug(level, subLevel, definition, shellOrigin);
        }
        BlockPos structureWorldOrigin = subLevel.getPlot().getCenterBlock();

        UUID shellId = UUID.randomUUID();
        List<ShellBinding> bindings = new ArrayList<>();
        for (ShellEndpoint endpoint : definition.endpoints()) {
            bindings.add(new ShellBinding(
                    shellId,
                    subLevel.getUniqueId(),
                    endpoint.id(),
                    endpoint.type(),
                    endpoint.shellFace(),
                    endpoint.internalInterfacePos()
            ));
        }

        BlockState shellBlockState = SFBlocks.SHELL_BLOCK.get().defaultBlockState();
        level.setBlock(shellOrigin, shellBlockState, Block.UPDATE_ALL);
        BlockEntity blockEntity = level.getBlockEntity(shellOrigin);
        ShellInstance instance = new ShellInstance(
                shellId,
                definition.definitionId(),
                subLevel.getUniqueId(),
                shellOrigin,
                structureWorldOrigin,
                definition.size(),
                List.copyOf(bindings)
        );
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock) {
            shellBlock.setInstance(instance);
        }

        return instance;
    }


    public static @Nullable ServerSubLevel getSubLevel(ServerLevel level, UUID id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container instanceof ServerSubLevelContainer serverContainer) {
            return (ServerSubLevel) serverContainer.getSubLevel(id);
        }
        return null;
    }

    /**
     * Shell blocks on Sable assembly plots store plot-local coordinates; pose sync and holding-chunk
     * hints must use the projected global position.
     */
    public static BlockPos resolveShellAnchorGlobalPos(ServerLevel level, BlockPos shellPos) {
        Vec3 global = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(shellPos));
        return BlockPos.containing(global);
    }

    private static Vector3d shellAnchorPoseTarget(ServerLevel level, BlockPos shellOrigin) {
        BlockPos global = resolveShellAnchorGlobalPos(level, shellOrigin);
        return new Vector3d(global.getX() + 0.5, REMOTE_Y, global.getZ() + 0.5);
    }

    private static Vector3d computeDebugVisualPosition(
            ServerSubLevel subLevel,
            @Nullable ShellDefinition definition,
            BlockPos visualOrigin
    ) {
        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        Vector3d visualPosition = new Vector3d(
                visualOrigin.getX(),
                visualOrigin.getY(),
                visualOrigin.getZ()
        );

        var centerOfMass = subLevel.getMassTracker().getCenterOfMass();
        if (centerOfMass != null) {
            visualPosition.add(
                    centerOfMass.x() - plotAnchor.getX(),
                    centerOfMass.y() - plotAnchor.getY(),
                    centerOfMass.z() - plotAnchor.getZ()
            );
            return visualPosition;
        }

        if (definition != null) {
            BlockPos size = definition.size();
            subLevel.logicalPose().rotationPoint().set(
                    plotAnchor.getX() + size.getX() * 0.5,
                    plotAnchor.getY() + size.getY() * 0.5,
                    plotAnchor.getZ() + size.getZ() * 0.5
            );
            visualPosition.add(size.getX() * 0.5, size.getY() * 0.5, size.getZ() * 0.5);
            return visualPosition;
        }

        subLevel.getPlot().updateBoundingBox();
        var bounds = subLevel.boundingBox();
        visualPosition.add(
                (bounds.minX() + bounds.maxX()) * 0.5 - plotAnchor.getX(),
                (bounds.minY() + bounds.maxY()) * 0.5 - plotAnchor.getY(),
                (bounds.minZ() + bounds.maxZ()) * 0.5 - plotAnchor.getZ()
        );
        return visualPosition;
    }

    private static void teleportSubLevelPose(
            ServerLevel level,
            ServerSubLevel subLevel,
            Vector3d target
    ) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            return;
        }

        Pose3d pose = subLevel.logicalPose();
        if (pose.position().distance(target) < 0.01) {
            return;
        }

        pose.position().set(target);
        SubLevelPhysicsSystem physicsSystem = serverContainer.physicsSystem();
        PhysicsPipeline pipeline = physicsSystem.getPipeline();
        pipeline.teleport(subLevel, pose.position(), pose.orientation());
        subLevel.updateLastPose();
        subLevel.updateBoundingBox();
    }

    /** Debug projection is visual-only; cancel residual motion so Sable does not fight the teleport. */
    private static void freezeSubLevelPhysics(ServerSubLevelContainer serverContainer, ServerSubLevel subLevel) {
        SubLevelPhysicsSystem physicsSystem = serverContainer.physicsSystem();
        RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
        if (handle == null || !handle.isValid()) {
            return;
        }

        Vector3d linearVelocity = new Vector3d();
        Vector3d angularVelocity = new Vector3d();
        handle.getAngularVelocity(angularVelocity).negate();
        handle.getLinearVelocity(linearVelocity).negate();
        subLevel.logicalPose().orientation().transformInverse(angularVelocity);
        subLevel.logicalPose().orientation().transformInverse(linearVelocity);

        ForceTotal forceTotal = new ForceTotal();
        forceTotal.applyLinearImpulse(linearVelocity.mul(subLevel.getMassTracker().getMass()));
        forceTotal.applyTorqueImpulse(subLevel.getMassTracker().getInertiaTensor().transform(angularVelocity));
        handle.applyForcesAndReset(forceTotal);
    }

    private static void ensureMainWorldChunkLoaded(ServerLevel level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, true);
    }

    /**
     * Resolves a shell interior sublevel, loading it from Sable holding storage when needed.
     */
    public static @Nullable ServerSubLevel resolveSubLevel(
            ServerLevel level,
            UUID id,
            @Nullable BlockPos shellPosHint
    ) {
        ServerSubLevel subLevel = getSubLevel(level, id);
        if (subLevel != null && !subLevel.isRemoved()) {
            return subLevel;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            return null;
        }

        SubLevelHoldingChunkMap holdingMap = serverContainer.getHoldingChunkMap();
        if (holdingMap.getHoldingSubLevel(id) != null) {
            triggerHoldingSubLevelLoad(level, serverContainer, shellPosHint);
            return getSubLevel(level, id);
        }

        if (shellPosHint != null) {
            BlockPos global = resolveShellAnchorGlobalPos(level, shellPosHint);
            ensureMainWorldChunkLoaded(level, global);
            ensureMainWorldChunkLoaded(level, new BlockPos(global.getX(), REMOTE_Y, global.getZ()));
            holdingMap.processChanges();
            subLevel = getSubLevel(level, id);
            if (subLevel != null && !subLevel.isRemoved()) {
                return subLevel;
            }
        }

        return null;
    }

    private static void triggerHoldingSubLevelLoad(
            ServerLevel level,
            ServerSubLevelContainer serverContainer,
            @Nullable BlockPos shellPosHint
    ) {
        if (shellPosHint != null) {
            BlockPos global = resolveShellAnchorGlobalPos(level, shellPosHint);
            ensureMainWorldChunkLoaded(level, global);
            ensureMainWorldChunkLoaded(level, new BlockPos(global.getX(), REMOTE_Y, global.getZ()));
        }
        serverContainer.getHoldingChunkMap().processChanges();
    }

    /**
     * Destroys the embedded/physical sublevel bound to a shell block and unloads it from Sable.
     */
    public static void destroyInstance(ServerLevel level, @Nullable ShellInstance instance) {
        if (instance == null) {
            return;
        }

        ServerSubLevel subLevel = getSubLevel(level, instance.subLevelId());
        if (subLevel == null || subLevel.isRemoved()) {
            return;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            return;
        }

        LevelPlot plot = subLevel.getPlot();
        plot.updateBoundingBox();

        if (plot instanceof ServerLevelPlot serverPlot) {
            serverPlot.kickAllEntities();
            serverPlot.destroyAllBlocks();
        }
        clearPlotBlocks(plot);

        serverContainer.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
    }

    private static void clearPlotBlocks(LevelPlot plot) {
        EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();
        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic chunkBounds = chunk.getBoundingBox();
            if (chunkBounds == null || chunkBounds == BoundingBox3i.EMPTY) {
                continue;
            }
            for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
                for (int y = chunkBounds.minY(); y <= chunkBounds.maxY(); y++) {
                    for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                        BlockPos localPos = new BlockPos(
                                x + chunk.getPos().getMinBlockX(),
                                y,
                                z + chunk.getPos().getMinBlockZ()
                        );
                        if (!accessor.getBlockState(localPos).isAir()) {
                            accessor.setBlock(localPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
        plot.updateBoundingBox();
    }

    /**
     * Plot-local endpoint coordinates (relative to copy min) map to world positions via the
     * sublevel plot center — never via the shell block's surface position.
     */
    public static BlockPos resolveInternalGlobalPos(ServerLevel level, UUID subLevelId, BlockPos internalRelativePos) {
        ServerSubLevel subLevel = getSubLevel(level, subLevelId);
        if (subLevel == null) {
            return BlockPos.ZERO;
        }
        return subLevel.getPlot().getCenterBlock().offset(internalRelativePos);
    }

    public static @Nullable BlockEntity getInternalBlockEntity(
            ServerLevel level,
            UUID subLevelId,
            BlockPos internalRelativePos
    ) {
        return getInternalBlockEntity(level, subLevelId, internalRelativePos, null);
    }

    public static @Nullable BlockEntity getInternalBlockEntity(
            ServerLevel level,
            UUID subLevelId,
            BlockPos internalRelativePos,
            @Nullable BlockPos shellPosHint
    ) {
        ServerSubLevel subLevel = shellPosHint != null
                ? resolveSubLevel(level, subLevelId, shellPosHint)
                : getSubLevel(level, subLevelId);
        if (subLevel == null) {
            return null;
        }
        return subLevel.getPlot().getEmbeddedLevelAccessor().getBlockEntity(internalRelativePos);
    }

    /**
     * Keeps a shell-bound sublevel's physics pose aligned with the shell block after the block moves
     * (e.g. on a Simulated physics assembly). Without this, embedded simulation keeps running but
     * main-world kinetic bridging drifts from the shell's current position.
     */
    public static boolean isSubLevelPoseOutOfSync(ServerLevel level, UUID subLevelId, BlockPos shellOrigin) {
        if (ShellDebugSettings.isEnabled()) {
            return false;
        }
        ServerSubLevel subLevel = resolveSubLevel(level, subLevelId, shellOrigin);
        if (subLevel == null || subLevel.isRemoved()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container instanceof ServerSubLevelContainer serverContainer
                    && serverContainer.getHoldingChunkMap().getHoldingSubLevel(subLevelId) != null) {
                return true;
            }
            return false;
        }
        Vector3d target = shellAnchorPoseTarget(level, shellOrigin);
        return subLevel.logicalPose().position().distance(target) >= 0.01;
    }

    public static void syncSubLevelPose(ServerLevel level, UUID subLevelId, BlockPos shellOrigin) {
        if (ShellDebugSettings.isEnabled()) {
            return;
        }
        ServerSubLevel subLevel = resolveSubLevel(level, subLevelId, shellOrigin);
        if (subLevel == null || subLevel.isRemoved()) {
            return;
        }
        teleportSubLevelPose(level, subLevel, shellAnchorPoseTarget(level, shellOrigin));
    }

    /** Projects a shell sublevel above its shell block for in-world debug inspection. */
    public static void projectSubLevelForDebug(
            ServerLevel level,
            ServerSubLevel subLevel,
            ShellDefinition definition,
            BlockPos shellOrigin
    ) {
        BlockPos visualOrigin = resolveShellAnchorGlobalPos(level, shellOrigin).above(1);
        Vector3d target = computeDebugVisualPosition(subLevel, definition, visualOrigin);
        teleportSubLevelPose(level, subLevel, target);
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container instanceof ServerSubLevelContainer serverContainer) {
            freezeSubLevelPhysics(serverContainer, subLevel);
        }
    }

    private static ServerSubLevel createSubLevel(ServerLevel level, BlockPos interfaceOrigin) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            throw new IllegalStateException("Sable sublevel container is not ready");
        }

        BlockPos globalOrigin = resolveShellAnchorGlobalPos(level, interfaceOrigin);
        Pose3d pose = new Pose3d();
        pose.position().set(new Vector3d(globalOrigin.getX() + 0.5, REMOTE_Y, globalOrigin.getZ() + 0.5));
        ServerSubLevel subLevel = (ServerSubLevel) serverContainer.allocateNewSubLevel(pose);
        subLevel.getPlot().newEmptyChunk(subLevel.getPlot().getCenterChunk());
        subLevel.setName("ShellForge Shell");
        return subLevel;
    }

    private static void moveSubLevelFarBelow(ServerSubLevel subLevel, BlockPos referencePos) {
        Pose3d pose = subLevel.logicalPose();
        pose.position().set(new Vector3d(referencePos.getX() + 0.5, REMOTE_Y, referencePos.getZ() + 0.5));
        subLevel.updateLastPose();
        subLevel.updateBoundingBox();
    }

    private static void materializeBlocks(
            ServerLevel level,
            ServerSubLevel subLevel,
            ShellDefinition definition
    ) {
        LevelPlot plot = subLevel.getPlot();
        EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();
        HolderLookup.Provider registries = level.registryAccess();

        for (ShellBlockSnapshot snapshot : definition.blocks()) {
            BlockPos localPos = snapshot.relativePos();
            accessor.setBlock(localPos, snapshot.state(), Block.UPDATE_ALL);
            if (snapshot.blockEntityTag() != null) {
                BlockEntity blockEntity = accessor.getBlockEntity(localPos);
                if (blockEntity != null) {
                    BlockPos globalPos = blockEntity.getBlockPos();
                    CompoundTag tag = snapshot.blockEntityTag().copy();
                    tag.putInt("x", globalPos.getX());
                    tag.putInt("y", globalPos.getY());
                    tag.putInt("z", globalPos.getZ());
                    blockEntity.loadWithComponents(tag, registries);
                }
            }
        }

        orientInterfaceFaces(accessor, definition);

        plot.updateBoundingBox();
        subLevel.updateBoundingBox();
        subLevel.updateLastPose();
    }

    private static void orientInterfaceFaces(EmbeddedPlotLevelAccessor accessor, ShellDefinition definition) {
        for (ShellEndpoint endpoint : definition.endpoints()) {
            BlockPos localPos = endpoint.internalInterfacePos();
            BlockState state = accessor.getBlockState(localPos);
            if (endpoint.type() == ShellEndpointType.STRESS && state.is(SFBlocks.STRESS_INTERFACE.get())) {
                accessor.setBlock(
                        localPos,
                        state.setValue(StressInterfaceBlock.FACING, endpoint.shellFace()),
                        Block.UPDATE_ALL
                );
            } else if (endpoint.type() == ShellEndpointType.REDSTONE && state.is(SFBlocks.REDSTONE_INTERFACE.get())) {
                accessor.setBlock(
                        localPos,
                        state.setValue(RedstoneInterfaceBlock.FACING, endpoint.shellFace()),
                        Block.UPDATE_ALL
                );
            } else if (endpoint.type() == ShellEndpointType.FLUID && state.is(SFBlocks.FLUID_INTERFACE.get())) {
                accessor.setBlock(
                        localPos,
                        state.setValue(FluidInterfaceBlock.FACING, endpoint.shellFace()),
                        Block.UPDATE_ALL
                );
            }
        }
    }

    private static List<ShellEndpoint> discoverEndpoints(
            ServerLevel level,
            BlockPos min,
            BlockPos max,
            Map<Direction, List<ShellEndpointType>> faceOverrides
    ) {
        List<ShellEndpoint> endpoints = new ArrayList<>();
        java.util.Set<Long> usedInterfacePositions = new java.util.HashSet<>();
        int index = 0;

        for (BlockPos scannedPos : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = scannedPos.immutable();
            BlockState state = level.getBlockState(pos);
            if (!state.is(SFBlocks.FACE_MARKER.get())) {
                continue;
            }

            Direction shellFace = state.getValue(FaceMarkerBlock.FACING);
            BlockPos interfacePos = findAdjacentInterface(level, pos);
            if (interfacePos == null) {
                throw new ShellCopyException("Face marker at " + pos.subtract(min).toShortString()
                        + " has no adjacent interface block for face " + shellFace.getName());
            }

            ShellEndpointType type = interfaceType(level.getBlockState(interfacePos));
            if (type == null) {
                throw new ShellCopyException("Adjacent block at " + interfacePos.subtract(min).toShortString()
                        + " is not a shell interface block");
            }

            BlockPos relative = interfacePos.subtract(min);
            if (usedInterfacePositions.add(relative.asLong())) {
                endpoints.add(new ShellEndpoint(
                        type.name().toLowerCase(Locale.ROOT) + "_" + index++,
                        type,
                        shellFace,
                        relative
                ));
            }
        }

        for (Map.Entry<Direction, List<ShellEndpointType>> override : faceOverrides.entrySet()) {
            Direction shellFace = override.getKey();
            for (ShellEndpointType type : override.getValue()) {
                List<BlockPos> boundaryInterfaces = findBoundaryInterfaces(level, min, max, shellFace, type);
                if (boundaryInterfaces.isEmpty()) {
                    throw new ShellCopyException("Command override --" + shellFace.getName() + " " + type.name().toLowerCase(Locale.ROOT)
                            + " could not find a matching interface on the selection boundary");
                }
                for (BlockPos interfacePos : boundaryInterfaces) {
                    BlockPos relative = interfacePos.subtract(min);
                    if (usedInterfacePositions.add(relative.asLong())) {
                        endpoints.add(new ShellEndpoint(
                                type.name().toLowerCase(Locale.ROOT) + "_" + index++,
                                type,
                                shellFace,
                                relative
                        ));
                    }
                }
            }
        }

        for (BlockPos scannedPos : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = scannedPos.immutable();
            BlockState state = level.getBlockState(pos);
            ShellEndpointType type = interfaceType(state);
            if (type == null) {
                continue;
            }
            BlockPos relative = pos.subtract(min);
            if (!usedInterfacePositions.add(relative.asLong())) {
                continue;
            }
            Direction shellFace = boundaryFace(pos, min, max);
            if (shellFace == null) {
                shellFace = nearestBoundaryFace(relative, min, max);
            }
            endpoints.add(new ShellEndpoint(
                    type.name().toLowerCase(Locale.ROOT) + "_" + index++,
                    type,
                    shellFace,
                    relative
            ));
        }

        if (endpoints.isEmpty()) {
            throw new ShellCopyException("No shell endpoints were discovered. Place face_marker blocks next to interface blocks, or use --north/--south/etc. overrides.");
        }

        return List.copyOf(endpoints);
    }

    private static @Nullable BlockPos findAdjacentInterface(ServerLevel level, BlockPos markerPos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = markerPos.relative(direction);
            BlockState state = level.getBlockState(neighbor);
            if (interfaceType(state) != null) {
                return neighbor;
            }
        }
        return null;
    }

    private static List<BlockPos> findBoundaryInterfaces(
            ServerLevel level,
            BlockPos min,
            BlockPos max,
            Direction shellFace,
            ShellEndpointType type
    ) {
        List<BlockPos> matches = new ArrayList<>();
        for (BlockPos scannedPos : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = scannedPos.immutable();
            if (boundaryFace(pos, min, max) != shellFace) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (interfaceType(state) == type) {
                matches.add(pos);
            }
        }
        return matches;
    }

    private static Direction nearestBoundaryFace(BlockPos relative, BlockPos min, BlockPos max) {
        BlockPos size = new BlockPos(max.getX() - min.getX(), max.getY() - min.getY(), max.getZ() - min.getZ());
        int dx = Math.min(relative.getX(), size.getX() - relative.getX());
        int dy = Math.min(relative.getY(), size.getY() - relative.getY());
        int dz = Math.min(relative.getZ(), size.getZ() - relative.getZ());
        if (dx <= dy && dx <= dz) {
            return relative.getX() == 0 ? Direction.WEST : Direction.EAST;
        }
        if (dy <= dz) {
            return relative.getY() == 0 ? Direction.DOWN : Direction.UP;
        }
        return relative.getZ() == 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private static @Nullable Direction boundaryFace(BlockPos pos, BlockPos min, BlockPos max) {
        if (pos.getX() == min.getX()) return Direction.WEST;
        if (pos.getX() == max.getX()) return Direction.EAST;
        if (pos.getY() == min.getY()) return Direction.DOWN;
        if (pos.getY() == max.getY()) return Direction.UP;
        if (pos.getZ() == min.getZ()) return Direction.NORTH;
        if (pos.getZ() == max.getZ()) return Direction.SOUTH;
        return null;
    }

    private static @Nullable ShellEndpointType interfaceType(BlockState state) {
        if (state.is(SFBlocks.STRESS_INTERFACE.get())) {
            return ShellEndpointType.STRESS;
        }
        if (state.is(SFBlocks.REDSTONE_INTERFACE.get())) {
            return ShellEndpointType.REDSTONE;
        }
        if (state.is(SFBlocks.FLUID_INTERFACE.get())) {
            return ShellEndpointType.FLUID;
        }
        return null;
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
}
