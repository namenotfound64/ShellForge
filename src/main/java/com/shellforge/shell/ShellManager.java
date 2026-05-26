package com.shellforge.shell;

import com.shellforge.block.FaceMarkerBlock;
import com.shellforge.block.RedstoneInterfaceBlock;
import com.shellforge.block.StressInterfaceBlock;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.registry.SFBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
    public static void serverTick(MinecraftServer server) {
        for (ShellBlockBlockEntity shell : ShellBlockBlockEntity.getActiveShells()) {
            shell.bridgeWirelessRedstone();
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
            projectSubLevelForDebug(level, subLevel, definition, shellOrigin.above(1));
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
        ServerSubLevel subLevel = getSubLevel(level, subLevelId);
        if (subLevel == null) {
            return null;
        }
        return subLevel.getPlot().getEmbeddedLevelAccessor().getBlockEntity(internalRelativePos);
    }

    private static ServerSubLevel createSubLevel(ServerLevel level, BlockPos interfaceOrigin) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            throw new IllegalStateException("Sable sublevel container is not ready");
        }

        Pose3d pose = new Pose3d();
        pose.position().set(new Vector3d(interfaceOrigin.getX() + 0.5, REMOTE_Y, interfaceOrigin.getZ() + 0.5));
        ServerSubLevel subLevel = (ServerSubLevel) serverContainer.allocateNewSubLevel(pose);
        subLevel.getPlot().newEmptyChunk(subLevel.getPlot().getCenterChunk());
        subLevel.setName("ShellForge Shell");
        return subLevel;
    }

    private static void projectSubLevelForDebug(
            ServerLevel level,
            ServerSubLevel subLevel,
            ShellDefinition definition,
            BlockPos visualOrigin
    ) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            return;
        }

        BlockPos plotAnchor = subLevel.getPlot().getCenterBlock();
        Pose3d pose = subLevel.logicalPose();
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
        } else {
            BlockPos size = definition.size();
            pose.rotationPoint().set(
                    plotAnchor.getX() + size.getX() * 0.5,
                    plotAnchor.getY() + size.getY() * 0.5,
                    plotAnchor.getZ() + size.getZ() * 0.5
            );
            visualPosition.add(size.getX() * 0.5, size.getY() * 0.5, size.getZ() * 0.5);
        }

        pose.position().set(visualPosition);
        SubLevelPhysicsSystem physicsSystem = serverContainer.physicsSystem();
        PhysicsPipeline pipeline = physicsSystem.getPipeline();
        pipeline.teleport(subLevel, pose.position(), pose.orientation());
        subLevel.updateLastPose();
        subLevel.updateBoundingBox();
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
