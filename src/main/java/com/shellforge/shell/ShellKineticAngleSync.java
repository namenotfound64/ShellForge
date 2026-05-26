package com.shellforge.shell;

import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.block.entity.StressInterfaceBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Shell kinetic bridge rotation sync.
 * <p>
 * Torsion springs drive consumers through {@code sequenceContext} where
 * {@code getEffectiveValue(rpm) = |relativeValue * rpm|} and {@code relativeValue = angle/rpm_at_start}.
 * Relayed RPM must match the spring output's RPM or the bearing turns the wrong amount.
 */
public final class ShellKineticAngleSync {
    private static final float EPSILON = 1e-4f;
    private static final int MAX_GRAPH_WALK = 64;

    /**
     * Active spring/sequencer turn on the sublevel side of a stress interface.
     */
    public record SpringSequenceState(
            SequencedGearshiftBlockEntity.SequenceContext context,
            float rpm,
            KineticBlockEntity source
    ) {
    }

    private ShellKineticAngleSync() {
    }

    public static boolean hasActiveTurnSequence(StressInterfaceBlockEntity start) {
        return findActiveSpringSequence(start) != null;
    }

    /**
     * Prefer the torsion spring Output entity (extra kinetics); it owns {@code sequenceContext} during a turn.
     */
    public static @Nullable SpringSequenceState findActiveSpringSequence(KineticBlockEntity start) {
        if (start.getLevel() == null) {
            return null;
        }

        KineticBlockEntity springOutput = findSpringOutputWithContext(start);
        if (springOutput != null) {
            return stateFromKinetic(springOutput);
        }

        SequencedGearshiftBlockEntity.SequenceContext context = findSequenceContextInChain(start);
        if (context == null) {
            return null;
        }
        return new SpringSequenceState(context, start.getTheoreticalSpeed(), start);
    }

    public static SequencedGearshiftBlockEntity.SequenceContext findSequenceContextInChain(KineticBlockEntity start) {
        if (start.getLevel() == null) {
            return null;
        }
        SequencedGearshiftBlockEntity.SequenceContext fromUpstream = walkSources(start);
        if (fromUpstream != null) {
            return fromUpstream;
        }
        return walkDownstreamConsumers(start);
    }

    public static SequencedGearshiftBlockEntity.SequenceContext probeSequenceContext(KineticBlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        if (blockEntity.sequenceContext != null) {
            return blockEntity.sequenceContext;
        }
        BlockEntity atPos = blockEntity.getLevel().getBlockEntity(blockEntity.getBlockPos());
        if (atPos != null) {
            BlockEntity extra = tryGetExtraKinetics(atPos);
            if (extra instanceof KineticBlockEntity extraKinetic && extraKinetic.sequenceContext != null) {
                return extraKinetic.sequenceContext;
            }
        }
        return null;
    }

    public static void refreshDownstreamSequenceContext(ShellBlockBlockEntity.PerFaceStressHub hub) {
        if (hub.getLevel() == null || hub.getLevel().isClientSide || hub.sequenceContext == null) {
            return;
        }

        KineticBlockEntity neighbor = externalFaceKinetic(hub);
        if (neighbor == null || !sourcesHub(neighbor, hub)) {
            return;
        }

        applySequenceContext(hub, neighbor);
    }

    public static void clearDownstreamSequenceContext(ShellBlockBlockEntity.PerFaceStressHub hub) {
        if (hub.getLevel() == null || hub.getLevel().isClientSide) {
            return;
        }

        KineticBlockEntity neighbor = externalFaceKinetic(hub);
        if (neighbor == null || !sourcesHub(neighbor, hub) || neighbor.sequenceContext == null) {
            return;
        }

        neighbor.sequenceContext = null;
        float previousSpeed = neighbor.getSpeed();
        neighbor.onSpeedChanged(previousSpeed);
    }

    public static void applySequenceContext(KineticBlockEntity from, KineticBlockEntity to) {
        SequencedGearshiftBlockEntity.SequenceContext previous = to.sequenceContext;
        to.sequenceContext = from.sequenceContext;
        if (sequenceContextChanged(previous, to.sequenceContext)) {
            float previousSpeed = to.getSpeed();
            to.onSpeedChanged(previousSpeed);
        }
    }

    public static Float findInheritedVisualAngle(KineticBlockEntity blockEntity, Direction.Axis axis) {
        ShellBlockBlockEntity.PerFaceStressHub hub = findBridgeHub(blockEntity, axis);
        if (hub == null || hub.sequenceContext != null) {
            return null;
        }
        if (Math.abs(hub.getBridgedVisualAngle()) < EPSILON) {
            return null;
        }
        return hub.getBridgedVisualAngle();
    }

    public static boolean shouldApplyShaftPhaseSync(KineticBlockEntity blockEntity, Direction.Axis axis) {
        ShellBlockBlockEntity.PerFaceStressHub hub = findBridgeHub(blockEntity, axis);
        if (hub == null) {
            return false;
        }
        return hub.sequenceContext == null && Math.abs(hub.getBridgedVisualAngle()) > EPSILON;
    }

    public static ShellBlockBlockEntity.PerFaceStressHub findBridgeHub(
            KineticBlockEntity blockEntity,
            Direction.Axis axis
    ) {
        if (blockEntity.getLevel() == null) {
            return null;
        }

        Set<BlockPos> visited = new HashSet<>();
        KineticBlockEntity current = blockEntity;
        for (int depth = 0; depth < MAX_GRAPH_WALK && current != null; depth++) {
            if (!visited.add(current.getBlockPos())) {
                break;
            }

            if (current instanceof ShellBlockBlockEntity.PerFaceStressHub hub) {
                if (hub.getFace().getAxis() == axis) {
                    return hub;
                }
                return null;
            }

            if (!current.hasSource()) {
                break;
            }
            current = resolveKineticAt(current.getLevel(), current.source);
        }
        return null;
    }

    public static int rotationOffsetForVisualAngle(KineticBlockEntity blockEntity, Direction.Axis axis, float visualAngle) {
        if (blockEntity.getLevel() == null) {
            return 0;
        }
        float time = blockEntity.getLevel().isClientSide
                ? AnimationTickHolder.getRenderTime(blockEntity.getLevel())
                : blockEntity.getLevel().getGameTime();
        float positionOffset = rotationOffset(blockEntity.getBlockState(), axis, blockEntity.getBlockPos());
        float offset = visualAngle - time * blockEntity.getSpeed() * 3f / 10f - positionOffset;
        return Math.round(normalizeAngle(offset));
    }

    public static void copySequenceContextIfPresent(KineticBlockEntity from, KineticBlockEntity to) {
        if (probeSequenceContext(from) != null) {
            applySequenceContext(from, to);
        }
    }

    public static boolean sequenceContextChanged(
            SequencedGearshiftBlockEntity.SequenceContext previous,
            SequencedGearshiftBlockEntity.SequenceContext current
    ) {
        if (previous == current) {
            return false;
        }
        if (previous == null || current == null) {
            return true;
        }
        return previous.instruction() != current.instruction()
                || Double.compare(previous.relativeValue(), current.relativeValue()) != 0;
    }

    public static boolean rpmChanged(float previous, float current) {
        return Math.abs(previous - current) > EPSILON
                || (Math.abs(previous) > EPSILON && Math.abs(current) > EPSILON
                && Math.signum(previous) != Math.signum(current));
    }

    private static @Nullable SpringSequenceState stateFromKinetic(KineticBlockEntity kinetic) {
        SequencedGearshiftBlockEntity.SequenceContext context = probeSequenceContext(kinetic);
        if (context == null) {
            return null;
        }
        float rpm = kinetic.getTheoreticalSpeed();
        if (Math.abs(rpm) < EPSILON) {
            rpm = kinetic.getGeneratedSpeed();
        }
        return new SpringSequenceState(context, rpm, kinetic);
    }

    private static @Nullable KineticBlockEntity findSpringOutputWithContext(KineticBlockEntity start) {
        Level level = start.getLevel();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.getBlockPos());

        while (!queue.isEmpty() && visited.size() < MAX_GRAPH_WALK) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                BlockEntity extra = tryGetExtraKinetics(blockEntity);
                if (extra instanceof KineticBlockEntity extraKinetic && probeSequenceContext(extraKinetic) != null) {
                    return extraKinetic;
                }
            }

            KineticBlockEntity kinetic = resolveKineticAt(level, pos);
            if (kinetic != null) {
                for (Direction direction : Direction.values()) {
                    BlockPos neighborPos = pos.relative(direction);
                    if (visited.contains(neighborPos)) {
                        continue;
                    }
                    KineticBlockEntity neighbor = resolveKineticAt(level, neighborPos);
                    if (!sourcesFromPos(neighbor, pos)) {
                        continue;
                    }
                    queue.add(neighborPos);
                }
            }
        }
        return null;
    }

    private static @Nullable KineticBlockEntity externalFaceKinetic(ShellBlockBlockEntity.PerFaceStressHub hub) {
        BlockPos externalPos = hub.getParentBlockEntity().getBlockPos().relative(hub.getFace());
        return resolveKineticAt(hub.getLevel(), externalPos);
    }

    private static boolean sourcesHub(KineticBlockEntity neighbor, ShellBlockBlockEntity.PerFaceStressHub hub) {
        if (!neighbor.hasSource()) {
            return false;
        }
        BlockPos source = neighbor.source;
        if (source == null) {
            return false;
        }
        BlockPos hubPos = hub.getBlockPos();
        BlockPos shellPos = hub.getParentBlockEntity().getBlockPos();
        return source.equals(hubPos) || source.equals(shellPos);
    }

    private static SequencedGearshiftBlockEntity.SequenceContext walkSources(KineticBlockEntity start) {
        Level level = start.getLevel();
        Set<BlockPos> visited = new HashSet<>();
        KineticBlockEntity current = start;
        for (int depth = 0; depth < MAX_GRAPH_WALK && current != null; depth++) {
            if (!visited.add(current.getBlockPos())) {
                break;
            }

            SequencedGearshiftBlockEntity.SequenceContext context = probeSequenceContext(current);
            if (context != null) {
                return context;
            }

            if (!current.hasSource()) {
                break;
            }
            current = resolveKineticAt(level, current.source);
        }
        return null;
    }

    private static SequencedGearshiftBlockEntity.SequenceContext walkDownstreamConsumers(KineticBlockEntity start) {
        Level level = start.getLevel();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.getBlockPos());

        while (!queue.isEmpty() && visited.size() < MAX_GRAPH_WALK) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                BlockEntity extra = tryGetExtraKinetics(blockEntity);
                if (extra instanceof KineticBlockEntity extraKinetic) {
                    SequencedGearshiftBlockEntity.SequenceContext context = probeSequenceContext(extraKinetic);
                    if (context != null) {
                        return context;
                    }
                }
            }

            KineticBlockEntity kinetic = resolveKineticAt(level, pos);
            if (kinetic != null) {
                SequencedGearshiftBlockEntity.SequenceContext context = probeSequenceContext(kinetic);
                if (context != null) {
                    return context;
                }
            }

            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = pos.relative(direction);
                if (visited.contains(neighborPos)) {
                    continue;
                }
                KineticBlockEntity neighbor = resolveKineticAt(level, neighborPos);
                if (!sourcesFromPos(neighbor, pos)) {
                    continue;
                }
                queue.add(neighborPos);
            }
        }
        return null;
    }

    private static boolean sourcesFromPos(@Nullable KineticBlockEntity neighbor, BlockPos pos) {
        if (neighbor == null || !neighbor.hasSource()) {
            return false;
        }
        BlockPos source = neighbor.source;
        return source != null && source.equals(pos);
    }

    static KineticBlockEntity resolveKineticAt(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof KineticBlockEntity kinetic) {
            return kinetic;
        }
        if (blockEntity != null) {
            BlockEntity extra = tryGetExtraKinetics(blockEntity);
            if (extra instanceof KineticBlockEntity kinetic) {
                return kinetic;
            }
        }
        return null;
    }

    private static float rotationOffset(BlockState state, Direction.Axis axis, Vec3i pos) {
        if (shouldOffset(axis, pos)) {
            return 22.5f;
        }
        return ICogWheel.isLargeCog(state) ? 11.25f : 0f;
    }

    private static boolean shouldOffset(Direction.Axis axis, Vec3i pos) {
        int x = axis == Direction.Axis.X ? 0 : pos.getX();
        int y = axis == Direction.Axis.Y ? 0 : pos.getY();
        int z = axis == Direction.Axis.Z ? 0 : pos.getZ();
        return (x + y + z) % 2 == 0;
    }

    private static float normalizeAngle(float angle) {
        angle %= 360f;
        if (angle < 0) {
            angle += 360f;
        }
        return angle;
    }

    private static BlockEntity tryGetExtraKinetics(BlockEntity blockEntity) {
        try {
            var method = blockEntity.getClass().getMethod("getExtraKinetics");
            Object result = method.invoke(blockEntity);
            if (result instanceof BlockEntity extra) {
                return extra;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }
}
