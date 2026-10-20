package com.shellforge.shell;

import com.shellforge.shell.ShellFluidPressureBridge.Endpoint;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Create flow/network ticks and tank transfers, with only world lookups replaced. */
class ShellFluidThroughNetworkTest {
    @Test
    void singleExternalPumpTransfersThroughTwoInterfaces() {
        runNetwork(false);
    }

    @Test
    void reversedExternalPumpPullsThroughBothInterfaces() {
        runNetwork(true);
    }

    private void runNetwork(boolean reversed) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false))).thenReturn(mock(ChunkAccess.class));
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
        Map<BlockPos, FluidTransportBehaviour> pipes = new HashMap<>();
        Map<BlockPos, FluidTank> tanks = new HashMap<>();
        BlockPos shellPos = new BlockPos(-10, 0, 0);
        BlockPos a = BlockPos.ZERO;
        BlockPos b = new BlockPos(3, 0, 0);
        FluidTank exteriorA = new FluidTank(4000);
        FluidTank exteriorB = new FluidTank(4000);
        FluidTank bufferA = new FluidTank(8100);
        FluidTank bufferB = new FluidTank(8100);
        (reversed ? exteriorB : exteriorA).fill(new FluidStack(Fluids.WATER, 2000), EXECUTE);
        tanks.put(new BlockPos(-8, 0, 0), exteriorA);
        tanks.put(shellPos, bufferA);
        tanks.put(shellPos.west(), exteriorB);
        tanks.put(a, bufferA);
        tanks.put(b, bufferB);
        when(level.getCapability(eq(Capabilities.FluidHandler.BLOCK), any(BlockPos.class), any(Direction.class)))
                .thenAnswer(call -> tanks.get(call.getArgument(1)));
        tanks.forEach((pos, tank) -> {
            BlockEntity be = mock(BlockEntity.class);
            when(be.getBlockPos()).thenReturn(pos);
            when(be.getLevel()).thenReturn(level);
            when(level.getBlockEntity(pos)).thenReturn(be);
        });
        FluidTransportBehaviour external = pipe(level, new BlockPos(-9, 0, 0));
        pipes.put(new BlockPos(-9, 0, 0), external);
        pipes.put(new BlockPos(1, 0, 0), pipe(level, new BlockPos(1, 0, 0)));
        pipes.put(new BlockPos(2, 0, 0), pipe(level, new BlockPos(2, 0, 0)));
        external.addPressure(Direction.EAST, !reversed, 64);
        external.addPressure(Direction.WEST, reversed, 64);
        List<ShellFluidRouting.Link> links = List.of(
                new ShellFluidRouting.Link(new Endpoint(level, shellPos, Direction.EAST), new Endpoint(level, a, Direction.EAST)),
                new ShellFluidRouting.Link(new Endpoint(level, shellPos, Direction.WEST), new Endpoint(level, b, Direction.WEST)));
        BlockEntity shell = mock(BlockEntity.class);
        when(shell.getLevel()).thenReturn(level);
        when(shell.getBlockPos()).thenReturn(shellPos);
        ShellFluidOutlet outlet = new ShellFluidOutlet();
        List<ShellFluidPressureBridge.AppliedPressure> applied = new ArrayList<>();
        try (var propagation = mockStatic(FluidPropagator.class);
             var behaviours = mockStatic(BlockEntityBehaviour.class)) {
            propagation.when(FluidPropagator::getPumpRange).thenReturn(16);
            propagation.when(() -> FluidPropagator.getPipe(eq(level), any(BlockPos.class)))
                    .thenAnswer(call -> pipes.get(call.getArgument(1)));
            propagation.when(() -> FluidPropagator.getPipeConnections(any(), any()))
                    .thenReturn(List.of(Direction.EAST, Direction.WEST));
            propagation.when(() -> FluidPropagator.hasFluidCapability(eq(level), any(BlockPos.class), any()))
                    .thenAnswer(call -> tanks.containsKey(call.getArgument(1)));
            behaviours.when(() -> BlockEntityBehaviour.get(eq(level), any(BlockPos.class), eq(FluidTransportBehaviour.TYPE)))
                    .thenAnswer(call -> pipes.get(call.getArgument(1)));
            for (int tick = 0; tick < 160; tick++) {
                when(level.getGameTime()).thenReturn((long) tick);
                applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
                applied.clear();
                var result = ShellFluidRouting.solve(links);
                applied.addAll(result.applied());
                pipes.values().forEach(FluidTransportBehaviour::tick);
                outlet.exchange(shell, Direction.WEST, bufferB,
                        result.outputPressure().getOrDefault(links.get(1).exterior(), 0f));
                assertEquals(2000, exteriorA.getFluidAmount() + bufferA.getFluidAmount()
                        + bufferB.getFluidAmount() + exteriorB.getFluidAmount(), "Fluid conservation at tick " + tick);
            }
            assertEquals(2000, (reversed ? exteriorA : exteriorB).getFluidAmount());
            assertTrue(bufferA.isEmpty());
            assertTrue(bufferB.isEmpty());
        } finally {
            applied.forEach(ShellFluidPressureBridge.AppliedPressure::remove);
        }
    }

    private FluidTransportBehaviour pipe(ServerLevel level, BlockPos pos) {
        SmartBlockEntity owner = mock(SmartBlockEntity.class);
        when(owner.getLevel()).thenReturn(level);
        when(owner.getBlockPos()).thenReturn(pos);
        when(owner.getBlockState()).thenReturn(Blocks.STONE.defaultBlockState());
        return new FluidTransportBehaviour(owner) {
            @Override
            public boolean canHaveFlowToward(BlockState state, Direction direction) {
                return direction.getAxis() == Direction.Axis.X;
            }
        };
    }
}
