package com.shellforge.shell;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Mirrors Create pipe pressure across a shell fluid face. This keeps actual
 * transfer inside Create's pipe networks instead of turning the interface into
 * an independent tank transport.
 */
public final class ShellFluidPressureBridge {
    private static final float EPSILON = 1e-4f;
    private static final Map<PipeConnection, List<PressureContribution>> CONTRIBUTIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ShellFluidPressureBridge() {
    }

    public record FacePressure(float intoEndpoint, float fromEndpoint) {
        public boolean hasAny() {
            return intoEndpoint > EPSILON || fromEndpoint > EPSILON;
        }
    }

    private static final class PressureContribution {
        private final boolean inbound;
        private final float pressure;

        private PressureContribution(boolean inbound, float pressure) {
            this.inbound = inbound;
            this.pressure = pressure;
        }
    }

    public record AppliedPressure(FluidTransportBehaviour pipe, Direction pipeSide, PressureContribution contribution) {
        public void remove() {
            PipeConnection connection = pipe.getConnection(pipeSide);
            List<PressureContribution> contributions = CONTRIBUTIONS.get(connection);
            // A topology rebuild may already have wiped this contribution.
            if (contributions != null && contributions.remove(contribution)) {
                pipe.addPressure(pipeSide, contribution.inbound, -contribution.pressure);
                if (contributions.isEmpty()) {
                    CONTRIBUTIONS.remove(connection);
                }
            }
        }
    }

    public static void onPressureWiped(PipeConnection connection) {
        CONTRIBUTIONS.remove(connection);
    }

    private static float nativePressure(PipeConnection connection, boolean inbound) {
        float pressure = connection.getPressure().get(inbound);
        for (PressureContribution contribution : CONTRIBUTIONS.getOrDefault(connection, List.of())) {
            if (contribution.inbound == inbound) {
                pressure -= contribution.pressure;
            }
        }
        return Math.max(0, pressure);
    }

    private static void apply(List<AppliedPressure> applied, FluidTransportBehaviour pipe,
                              Direction side, boolean inbound, float pressure) {
        PipeConnection connection = pipe.getConnection(side);
        if (connection == null) {
            return;
        }
        PressureContribution contribution = new PressureContribution(inbound, pressure);
        CONTRIBUTIONS.computeIfAbsent(connection, ignored -> new ArrayList<>()).add(contribution);
        pipe.addPressure(side, inbound, pressure);
        applied.add(new AppliedPressure(pipe, side, contribution));
    }

    public static FacePressure readPipePressure(Level level, BlockPos endpointPos, Direction endpointToPipe) {
        if (level == null || level.isClientSide()) {
            return new FacePressure(0, 0);
        }
        BlockPos pipePos = endpointPos.relative(endpointToPipe);
        if (!level.isLoaded(pipePos)) {
            return new FacePressure(0, 0);
        }
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
        if (pipe == null) {
            return new FacePressure(0, 0);
        }
        Direction pipeSide = endpointToPipe.getOpposite();
        PipeConnection connection = pipe.getConnection(pipeSide);
        if (connection == null) {
            return new FacePressure(0, 0);
        }
        return new FacePressure(
                nativePressure(connection, false),
                nativePressure(connection, true)
        );
    }

    public static List<AppliedPressure> mirrorPressure(
            Level level,
            BlockPos endpointPos,
            Direction endpointToPipe,
            boolean flowFromEndpoint,
            float pressure
    ) {
        return mirrorPressure(level, endpointPos, endpointToPipe, flowFromEndpoint, pressure,
                Math.max(1, FluidPropagator.getPumpRange()), new HashSet<>(), (endpoint, inbound, value, remaining) -> {});
    }

    public record Endpoint(Level level, BlockPos pos, Direction side) {}

    static int remainingRange(Endpoint endpoint, boolean intoEndpoint) {
        int range = Math.max(1, FluidPropagator.getPumpRange());
        Queue<PipeSide> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(new PipeSide(endpoint.pos().relative(endpoint.side()), endpoint.side().getOpposite(), 0, 0));
        while (!queue.isEmpty()) {
            PipeSide current = queue.remove();
            if (!seen.add(current.pos()) || !endpoint.level().isLoaded(current.pos())) continue;
            BlockState state = endpoint.level().getBlockState(current.pos());
            if (state.getBlock() instanceof PumpBlock) return Math.max(0, range - current.distance());
            if (current.distance() >= range) continue;
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(endpoint.level(), current.pos());
            if (pipe == null) continue;
            for (Direction side : FluidPropagator.getPipeConnections(state, pipe)) {
                if (side == current.side()) continue;
                PipeConnection connection = pipe.getConnection(side);
                if (connection == null || nativePressure(connection, intoEndpoint) <= EPSILON) continue;
                queue.add(new PipeSide(current.pos().relative(side), side.getOpposite(), current.distance() + 1, 0));
            }
        }
        // Other Create-compatible pressure providers may not be PumpBlock instances.
        return range;
    }

    record PipeNode(Level level, BlockPos pos) {}

    @FunctionalInterface
    interface EndpointVisitor {
        void reached(Endpoint endpoint, boolean intoEndpoint, float pressure, int remaining);
    }

    static List<AppliedPressure> mirrorPressure(Level level, BlockPos endpointPos, Direction endpointToPipe,
            boolean flowFromEndpoint, float pressure, int maxDistance, Set<PipeNode> visited, EndpointVisitor visitor) {
        if (level == null || level.isClientSide() || pressure <= EPSILON || maxDistance <= 0) {
            return List.of();
        }

        BlockPos firstPipe = endpointPos.relative(endpointToPipe);
        Direction firstPipeSide = endpointToPipe.getOpposite();
        if (!level.isLoaded(firstPipe) || FluidPropagator.getPipe(level, firstPipe) == null) {
            return List.of();
        }

        List<AppliedPressure> applied = new ArrayList<>();
        Queue<PipeSide> frontier = new ArrayDeque<>();
        if (!visited.add(new PipeNode(level, firstPipe))) {
            return List.of();
        }
        frontier.add(new PipeSide(firstPipe, firstPipeSide, 1, pressure));

        while (!frontier.isEmpty()) {
            PipeSide current = frontier.remove();
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, current.pos());
            if (pipe == null) {
                continue;
            }
            BlockState state = level.getBlockState(current.pos());
            // Pumps own the pressure on their two independent networks.
            if (state.getBlock() instanceof PumpBlock) {
                continue;
            }
            PipeConnection connection = pipe.getConnection(current.side());
            if (connection == null || !pipe.canHaveFlowToward(state, current.side())) {
                continue;
            }

            List<Direction> exits = new ArrayList<>();
            for (Direction nextSide : FluidPropagator.getPipeConnections(state, pipe)) {
                if (nextSide == current.side()) {
                    continue;
                }
                BlockPos nextPos = current.pos().relative(nextSide);
                if (!level.isLoaded(nextPos) || visited.contains(new PipeNode(level, nextPos))) {
                    continue;
                }
                FluidTransportBehaviour nextPipe = FluidPropagator.getPipe(level, nextPos);
                if (nextPipe != null) {
                    BlockState nextState = level.getBlockState(nextPos);
                    if (current.distance() >= maxDistance || nextState.getBlock() instanceof PumpBlock
                            || !nextPipe.canHaveFlowToward(nextState, nextSide.getOpposite())) {
                        continue;
                    }
                } else if (!FluidPropagator.hasFluidCapability(level, nextPos, nextSide.getOpposite())
                        && !FluidPropagator.isOpenEnd(level, current.pos(), nextSide)) {
                    continue;
                }
                exits.add(nextSide);
            }
            apply(applied, pipe, current.side(), flowFromEndpoint, current.pressure());
            float branchPressure = current.pressure() / Math.max(1, exits.size());
            for (Direction exit : exits) {
                // Write the exit once; never enqueue it as another entrance to this pipe.
                apply(applied, pipe, exit, !flowFromEndpoint, branchPressure);
                BlockPos nextPos = current.pos().relative(exit);
                if (FluidPropagator.getPipe(level, nextPos) != null && visited.add(new PipeNode(level, nextPos))) {
                    frontier.add(new PipeSide(nextPos, exit.getOpposite(), current.distance() + 1, branchPressure));
                } else if (FluidPropagator.getPipe(level, nextPos) == null) {
                    visitor.reached(new Endpoint(level, nextPos, exit.getOpposite()), flowFromEndpoint,
                            branchPressure, maxDistance - current.distance());
                }
            }
        }
        return applied;
    }

    private record PipeSide(BlockPos pos, Direction side, int distance, float pressure) {
    }
}
