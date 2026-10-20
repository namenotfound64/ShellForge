package com.shellforge.shell;

import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.registry.SFBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShellFluidOutputTickTest {
    @Test
    void internalPumpDrivesOutputOncePerTickAndStopsWithPressure() {
        exercise(false);
    }

    @Test
    void internalPumpDrivesExternalPipeWithoutDirectTankTransfer() {
        exercise(true);
    }

    private void exercise(boolean withPipe) {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos portPos = new BlockPos(10, 0, 0);
        UUID id = UUID.randomUUID();
        ShellBlockBlockEntity shell = new ShellBlockBlockEntity(BlockPos.ZERO, SFBlocks.SHELL_BLOCK.get().defaultBlockState());
        shell.setInstance(new ShellInstance(id, id, id, BlockPos.ZERO, portPos, new BlockPos(3, 3, 3),
                List.of(new ShellBinding(id, id, "east", ShellEndpointType.FLUID, Direction.EAST, portPos))));
        shell.setLevel(level);
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
        FluidTank source = new FluidTank(8100);
        source.fill(new FluidStack(Fluids.WATER, 1000), EXECUTE);
        FluidTank target = new FluidTank(8100);
        when(level.getCapability(Capabilities.FluidHandler.BLOCK, BlockPos.ZERO.east(), Direction.WEST)).thenReturn(target);
        FluidInterfaceBlockEntity port = mock(FluidInterfaceBlockEntity.class);
        when(port.getLevel()).thenReturn(level);
        when(port.getBlockPos()).thenReturn(portPos);
        when(port.getFluidHandler(Direction.WEST)).thenReturn(source);
        FluidTransportBehaviour internalPipe = mock(FluidTransportBehaviour.class);
        PipeConnection internalEnd = new PipeConnection(Direction.EAST);
        internalEnd.addPressure(false, 64);
        when(internalPipe.getConnection(Direction.EAST)).thenReturn(internalEnd);
        FluidTransportBehaviour externalPipe = mock(FluidTransportBehaviour.class);
        PipeConnection entrance = new PipeConnection(Direction.WEST);
        PipeConnection exit = new PipeConnection(Direction.EAST);
        when(externalPipe.getConnection(Direction.WEST)).thenReturn(entrance);
        when(externalPipe.getConnection(Direction.EAST)).thenReturn(exit);
        when(externalPipe.canHaveFlowToward(any(), any())).thenReturn(true);
        doAnswer(call -> {
            PipeConnection connection = call.getArgument(0) == Direction.WEST ? entrance : exit;
            connection.addPressure(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(externalPipe).addPressure(any(), anyBoolean(), anyFloat());
        try (var manager = mockStatic(ShellManager.class); var propagation = mockStatic(FluidPropagator.class)) {
            manager.when(() -> ShellManager.resolveShellAnchorGlobalPos(level, BlockPos.ZERO)).thenReturn(BlockPos.ZERO);
            manager.when(() -> ShellManager.getInternalBlockEntity(level, id, portPos, BlockPos.ZERO)).thenReturn(port);
            propagation.when(() -> FluidPropagator.getPipe(level, portPos.west())).thenReturn(internalPipe);
            propagation.when(FluidPropagator::getPumpRange).thenReturn(16);
            if (withPipe) {
                propagation.when(() -> FluidPropagator.getPipe(level, BlockPos.ZERO.east())).thenReturn(externalPipe);
                propagation.when(() -> FluidPropagator.getPipeConnections(any(), eq(externalPipe)))
                        .thenReturn(List.of(Direction.WEST, Direction.EAST));
                propagation.when(() -> FluidPropagator.isOpenEnd(level, BlockPos.ZERO.east(), Direction.EAST)).thenReturn(true);
            }
            shell.tickServerMaintenance();
            shell.tickServerMaintenance();
            assertEquals(withPipe ? 0 : 32, target.getFluidAmount());
            assertEquals(1000, source.getFluidAmount() + target.getFluidAmount());
            if (withPipe) {
                assertEquals(64, entrance.getPressure().get(true));
                assertEquals(64, exit.getPressure().get(false));
                assertEquals(0, entrance.getPressure().get(false));
            }
            internalEnd.wipePressure();
            when(level.getGameTime()).thenReturn(1L);
            shell.tickServerMaintenance();
            assertEquals(withPipe ? 0 : 32, target.getFluidAmount());
            assertEquals(0, entrance.getPressure().get(true));
            internalEnd.addPressure(false, 64);
            when(level.getGameTime()).thenReturn(2L);
            shell.tickServerMaintenance();
            assertEquals(withPipe ? 0 : 64, target.getFluidAmount());
            shell.clearInstance();
        } finally {
            shell.clearInstance();
        }
    }
}
