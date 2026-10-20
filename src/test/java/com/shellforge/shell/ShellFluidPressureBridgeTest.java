package com.shellforge.shell;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShellFluidPressureBridgeTest {
    private final Level level = mock(Level.class);
    private final Map<BlockPos, FluidTransportBehaviour> pipes = new HashMap<>();
    private final List<ShellFluidPressureBridge.AppliedPressure> applied = new ArrayList<>();
    private MockedStatic<FluidPropagator> propagator;

    @BeforeEach
    void setUp() {
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
        propagator = mockStatic(FluidPropagator.class);
        propagator.when(FluidPropagator::getPumpRange).thenReturn(4);
        propagator.when(() -> FluidPropagator.getPipe(eq(level), any(BlockPos.class)))
                .thenAnswer(call -> pipes.get(call.getArgument(1)));
        propagator.when(() -> FluidPropagator.isOpenEnd(eq(level), any(BlockPos.class), any(Direction.class)))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
        propagator.close();
    }

    @Test
    void straightPipeDoesNotReflectPressureIntoItsEntrance() {
        FluidTransportBehaviour first = pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour second = pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        mirror(true, 64);
        assertPressure(first, Direction.WEST, 64, 0);
        assertPressure(first, Direction.EAST, 0, 64);
        assertPressure(second, Direction.WEST, 64, 0);
        assertPressure(second, Direction.EAST, 0, 64);
    }

    @Test
    void reversedFlowAndBranchesKeepTheirDirection() {
        FluidTransportBehaviour first = pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST, Direction.SOUTH);
        FluidTransportBehaviour second = pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        mirror(false, 64);
        assertPressure(first, Direction.WEST, 0, 64);
        assertPressure(first, Direction.EAST, 32, 0);
        assertPressure(first, Direction.SOUTH, 32, 0);
        assertPressure(second, Direction.WEST, 0, 32);
    }

    @Test
    void nativeReadExcludesAllMirroredContributions() {
        FluidTransportBehaviour pipe = pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        pipe.addPressure(Direction.WEST, true, 12);
        mirror(true, 64);
        mirror(true, 64);
        assertEquals(12, ShellFluidPressureBridge.readPipePressure(level, BlockPos.ZERO, Direction.EAST).fromEndpoint());
        applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
        assertPressure(pipe, Direction.WEST, 12, 0);
    }

    @Test
    void wipedContributionDoesNotSubtractNewPumpPressure() {
        FluidTransportBehaviour pipe = pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        mirror(true, 64);
        for (Direction side : List.of(Direction.WEST, Direction.EAST)) {
            PipeConnection connection = pipe.getConnection(side);
            connection.wipePressure();
        }
        pipe.addPressure(Direction.WEST, true, 10);
        mirror(true, 64);
        // Removing old tokens must not remove equal-valued tokens from the new generation.
        applied.subList(0, 2).forEach(ShellFluidPressureBridge.AppliedPressure::remove);
        assertPressure(pipe, Direction.WEST, 74, 0);
        applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
        assertPressure(pipe, Direction.WEST, 10, 0);
    }

    @Test
    void rangeCountsPipeDistanceInsteadOfQueueEntries() {
        for (int x = 1; x <= 6; x++) {
            pipe(new BlockPos(x, 0, 0), Direction.WEST, Direction.EAST);
        }
        mirror(true, 64);
        assertPressure(pipes.get(new BlockPos(4, 0, 0)), Direction.WEST, 64, 0);
        assertPressure(pipes.get(new BlockPos(5, 0, 0)), Direction.WEST, 0, 0);
    }

    @Test
    void disconnectedAndUnloadedPipesAreNotTraversed() {
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST, Direction.SOUTH);
        FluidTransportBehaviour disconnected = pipe(new BlockPos(2, 0, 0), Direction.NORTH, Direction.SOUTH);
        FluidTransportBehaviour unloaded = pipe(new BlockPos(1, 0, 1), Direction.NORTH, Direction.SOUTH);
        when(level.isLoaded(new BlockPos(1, 0, 1))).thenReturn(false);
        mirror(true, 64);
        assertPressure(disconnected, Direction.NORTH, 0, 0);
        assertPressure(unloaded, Direction.NORTH, 0, 0);
    }

    @Test
    void loopDoesNotReenterVisitedPipes() {
        FluidTransportBehaviour entrance = pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST, Direction.SOUTH);
        pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.SOUTH);
        pipe(new BlockPos(1, 0, 1), Direction.NORTH, Direction.EAST);
        pipe(new BlockPos(2, 0, 1), Direction.NORTH, Direction.WEST);
        mirror(true, 64);
        assertPressure(entrance, Direction.WEST, 64, 0);
        assertPressure(entrance, Direction.EAST, 0, 32);
        assertPressure(entrance, Direction.SOUTH, 0, 32);
        assertTrue(applied.size() <= 8);
    }

    private FluidTransportBehaviour pipe(BlockPos pos, Direction... sides) {
        FluidTransportBehaviour pipe = mock(FluidTransportBehaviour.class);
        Map<Direction, PipeConnection> connections = new EnumMap<>(Direction.class);
        for (Direction side : sides) {
            connections.put(side, new PipeConnection(side));
        }
        when(pipe.getConnection(any(Direction.class))).thenAnswer(call -> connections.get(call.getArgument(0)));
        when(pipe.canHaveFlowToward(any(), any(Direction.class)))
                .thenAnswer(call -> connections.containsKey(call.getArgument(1)));
        doAnswer(call -> {
            connections.get(call.getArgument(0)).addPressure(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(pipe).addPressure(any(Direction.class), anyBoolean(), anyFloat());
        propagator.when(() -> FluidPropagator.getPipeConnections(any(), eq(pipe))).thenReturn(List.of(sides));
        pipes.put(pos, pipe);
        return pipe;
    }

    @Test
    void pressureCrossesInteriorAndAnotherFaceRegardlessOfBindingOrder() {
        var links = throughLinks();
        FluidTransportBehaviour inlet = pipe(new BlockPos(-9, 0, 0), Direction.WEST, Direction.EAST);
        inlet.getConnection(Direction.WEST).addPressure(false, 64);
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour outlet = pipe(new BlockPos(-11, 0, 0), Direction.WEST, Direction.EAST);
        for (var ordered : List.of(links, links.reversed())) {
            var result = ShellFluidRouting.solve(ordered);
            applied.addAll(result.applied());
            assertEquals(64f, result.outputPressure().get(links.get(1).exterior()));
            assertPressure(outlet, Direction.EAST, 64, 0);
            assertPressure(outlet, Direction.WEST, 0, 64);
            applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
            applied.clear();
        }
        inlet.getConnection(Direction.WEST).wipePressure();
        assertTrue(ShellFluidRouting.solve(links).applied().isEmpty());
    }

    @Test
    void suctionTraversesTheSameInteriorInReverse() {
        var links = throughLinks();
        FluidTransportBehaviour inlet = pipe(new BlockPos(-9, 0, 0), Direction.WEST, Direction.EAST);
        inlet.getConnection(Direction.WEST).addPressure(true, 64);
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour outlet = pipe(new BlockPos(-11, 0, 0), Direction.WEST, Direction.EAST);
        var result = ShellFluidRouting.solve(links);
        applied.addAll(result.applied());
        assertEquals(-64f, result.outputPressure().get(links.get(1).exterior()));
        assertPressure(outlet, Direction.EAST, 0, 64);
        assertPressure(outlet, Direction.WEST, 64, 0);
    }

    @Test
    void disconnectedInteriorDoesNotConnectShellFaces() {
        var links = throughLinks();
        FluidTransportBehaviour inlet = pipe(new BlockPos(-9, 0, 0), Direction.WEST, Direction.EAST);
        inlet.getConnection(Direction.WEST).addPressure(false, 64);
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        var result = ShellFluidRouting.solve(links);
        applied.addAll(result.applied());
        assertFalse(result.outputPressure().containsKey(links.get(1).exterior()));
    }

    @Test
    void crossingSecondFaceDoesNotResetRemainingRange() {
        var links = throughLinks();
        FluidTransportBehaviour inlet = pipe(new BlockPos(-9, 0, 0), Direction.WEST, Direction.EAST);
        inlet.getConnection(Direction.WEST).addPressure(false, 64);
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(-11, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour last = pipe(new BlockPos(-12, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour tooFar = pipe(new BlockPos(-13, 0, 0), Direction.WEST, Direction.EAST);
        applied.addAll(ShellFluidRouting.solve(links).applied());
        assertPressure(last, Direction.EAST, 64, 0);
        assertPressure(tooFar, Direction.EAST, 0, 0);
    }

    private List<ShellFluidRouting.Link> throughLinks() {
        BlockPos shell = new BlockPos(-10, 0, 0);
        return List.of(new ShellFluidRouting.Link(
                        new ShellFluidPressureBridge.Endpoint(level, shell, Direction.EAST),
                        new ShellFluidPressureBridge.Endpoint(level, BlockPos.ZERO, Direction.EAST)),
                new ShellFluidRouting.Link(
                        new ShellFluidPressureBridge.Endpoint(level, shell, Direction.WEST),
                        new ShellFluidPressureBridge.Endpoint(level, new BlockPos(3, 0, 0), Direction.WEST)));
    }

    @Test
    void originalPumpDistanceIsDeductedBeforeEnteringShell() {
        var links = throughLinks();
        FluidTransportBehaviour inlet = pipe(new BlockPos(-9, 0, 0), Direction.WEST, Direction.EAST);
        inlet.getConnection(Direction.WEST).addPressure(false, 64);
        inlet.getConnection(Direction.EAST).addPressure(true, 64);
        var pumpState = mock(net.minecraft.world.level.block.state.BlockState.class);
        when(pumpState.getBlock()).thenReturn(mock(com.simibubi.create.content.fluids.pump.PumpBlock.class));
        when(level.getBlockState(new BlockPos(-8, 0, 0))).thenReturn(pumpState);
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour last = pipe(new BlockPos(-11, 0, 0), Direction.WEST, Direction.EAST);
        FluidTransportBehaviour tooFar = pipe(new BlockPos(-12, 0, 0), Direction.WEST, Direction.EAST);
        applied.addAll(ShellFluidRouting.solve(links).applied());
        assertPressure(last, Direction.EAST, 64, 0);
        assertPressure(tooFar, Direction.EAST, 0, 0);
    }

    @Test
    void crossBoundaryLoopDoesNotReenterItsSourceOrAmplifyPressure() {
        propagator.when(FluidPropagator::getPumpRange).thenReturn(16);
        var links = throughLinks();
        FluidTransportBehaviour inlet = pipe(new BlockPos(-9, 0, 0), Direction.WEST, Direction.SOUTH);
        inlet.getConnection(Direction.WEST).addPressure(false, 64);
        pipe(new BlockPos(1, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(2, 0, 0), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(-11, 0, 0), Direction.EAST, Direction.SOUTH);
        pipe(new BlockPos(-11, 0, 1), Direction.NORTH, Direction.EAST);
        pipe(new BlockPos(-10, 0, 1), Direction.WEST, Direction.EAST);
        pipe(new BlockPos(-9, 0, 1), Direction.WEST, Direction.NORTH);
        for (int tick = 0; tick < 20; tick++) {
            applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
            applied.clear();
            var result = ShellFluidRouting.solve(links);
            applied.addAll(result.applied());
            assertFalse(result.outputPressure().containsKey(links.getFirst().exterior()));
            assertEquals(64f, result.outputPressure().get(links.get(1).exterior()));
            assertPressure(inlet, Direction.WEST, 0, 64);
        }
    }

    private void mirror(boolean outbound, float pressure) {
        applied.addAll(ShellFluidPressureBridge.mirrorPressure(level, BlockPos.ZERO, Direction.EAST, outbound, pressure));
    }

    private static void assertPressure(FluidTransportBehaviour pipe, Direction side, float inbound, float outward) {
        assertEquals(inbound, pipe.getConnection(side).getPressure().get(true), 0.001f);
        assertEquals(outward, pipe.getConnection(side).getPressure().get(false), 0.001f);
    }
}
