package com.shellforge.shell;

import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.registry.SFBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShellFluidCapabilityTest {
    @Test
    void ordinaryPositionExposesStableIndependentSidedCapabilities() {
        Level level = mock(Level.class);
        BlockState state = SFBlocks.SHELL_BLOCK.get().defaultBlockState();
        ShellBlockBlockEntity shell = boundShell(level, state);
        try {
            IFluidHandler north = query(level, state, shell, Direction.NORTH);
            IFluidHandler south = query(level, state, shell, Direction.SOUTH);
            assertNotNull(north);
            assertNotNull(south);
            assertNotSame(north, south);
            assertSame(north, query(level, state, shell, Direction.NORTH));
            assertNull(query(level, state, shell, Direction.EAST));
            assertNull(query(level, state, shell, null));
            shell.clearInstance();
            assertNull(query(level, state, shell, Direction.NORTH));
        } finally {
            shell.clearInstance();
        }
    }

    @Test
    void neighborNotificationDoesNotStartAnotherFluidNeighborUpdate() {
        ServerLevel level = mock(ServerLevel.class);
        ShellBlockBlockEntity shell = boundShell(level, SFBlocks.SHELL_BLOCK.get().defaultBlockState());
        try {
            shell.onNeighborChanged(BlockPos.ZERO.north());
            verify(level, never()).updateNeighborsAt(any(), any());
            verify(level, never()).invalidateCapabilities(any(BlockPos.class));
        } finally {
            shell.clearInstance();
        }
    }

    private static ShellBlockBlockEntity boundShell(Level level, BlockState state) {
        ShellBlockBlockEntity shell = new ShellBlockBlockEntity(BlockPos.ZERO, state);
        UUID shellId = UUID.randomUUID();
        UUID subLevelId = UUID.randomUUID();
        shell.setInstance(new ShellInstance(shellId, UUID.randomUUID(), subLevelId,
                BlockPos.ZERO, BlockPos.ZERO, new BlockPos(3, 3, 3), List.of(
                new ShellBinding(shellId, subLevelId, "north", ShellEndpointType.FLUID,
                        Direction.NORTH, BlockPos.ZERO),
                new ShellBinding(shellId, subLevelId, "south", ShellEndpointType.FLUID,
                        Direction.SOUTH, new BlockPos(0, 0, 2)))));
        shell.setLevel(level);
        return shell;
    }

    private static IFluidHandler query(Level level, BlockState state, ShellBlockBlockEntity shell, Direction side) {
        return Capabilities.FluidHandler.BLOCK.getCapability(level, BlockPos.ZERO, state, shell, side);
    }
}
