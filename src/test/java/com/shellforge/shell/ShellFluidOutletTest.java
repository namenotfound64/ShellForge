package com.shellforge.shell;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShellFluidOutletTest {
    private final Level level = mock(Level.class);
    private final BlockEntity shell = mock(BlockEntity.class);
    private final ShellFluidOutlet outlet = new ShellFluidOutlet();
    private final FluidTank source = new FluidTank(8100);
    private final BlockPos targetPos = BlockPos.ZERO.east();

    @BeforeEach
    void setUp() {
        when(shell.getLevel()).thenReturn(level);
        when(shell.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.dimensionType()).thenReturn(mock(DimensionType.class));
        when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        source.fill(new FluidStack(Fluids.WATER, 2000), EXECUTE);
    }

    @Test
    void internalPressureFillsAdjacentTankWithoutExternalPump() {
        FluidTank target = new FluidTank(100);
        when(level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, Direction.WEST)).thenReturn(target);
        try (var propagation = mockStatic(FluidPropagator.class)) {
            assertEquals(32, outlet.push(shell, Direction.EAST, source, 64));
            assertEquals(1968, source.getFluidAmount());
            assertEquals(32, target.getFluidAmount());
            assertEquals(0, outlet.push(shell, Direction.EAST, source, 0));
            assertEquals(0, outlet.push(shell, Direction.EAST, source, -64));
            assertEquals(68, outlet.push(shell, Direction.EAST, source, 256));
            assertEquals(0, outlet.push(shell, Direction.EAST, source, 256));
            assertEquals(2000, source.getFluidAmount() + target.getFluidAmount());
        }
    }

    @Test
    void attachedPipeIsLeftToNativeNetworkAndUnloadedTargetsAreSkipped() {
        try (var propagation = mockStatic(FluidPropagator.class)) {
            propagation.when(() -> FluidPropagator.getPipe(level, targetPos))
                    .thenReturn(mock(FluidTransportBehaviour.class));
            assertEquals(0, outlet.push(shell, Direction.EAST, source, 64));
            when(level.isLoaded(targetPos)).thenReturn(false);
            assertEquals(0, outlet.push(shell, Direction.EAST, source, 64));
            assertEquals(2000, source.getFluidAmount());
            verify(level, never()).getCapability(Capabilities.FluidHandler.BLOCK, targetPos, Direction.WEST);
        }
    }

    @Test
    void blockedAndIncompatibleTargetsRetainFluid() {
        FluidTank target = new FluidTank(4000);
        target.fill(new FluidStack(Fluids.LAVA, 1000), EXECUTE);
        try (var propagation = mockStatic(FluidPropagator.class)) {
            assertEquals(0, outlet.push(shell, Direction.EAST, source, 64));
            when(level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, Direction.WEST)).thenReturn(target);
            assertEquals(0, outlet.push(shell, Direction.EAST, source, 64));
            assertEquals(2000, source.getFluidAmount());
        }
    }

    @Test
    void rejectedExecutionIsReturnedToInterfaceBuffer() {
        IFluidHandler target = mock(IFluidHandler.class);
        when(target.fill(any(), eq(SIMULATE))).thenReturn(100);
        when(target.fill(any(), eq(EXECUTE))).thenReturn(25);
        assertEquals(25, ShellFluidOutlet.transfer(source, target, new FluidStack(Fluids.WATER, 100)));
        assertEquals(1975, source.getFluidAmount());
    }

    @Test
    void openEndAccumulatesPersistsAndPlacesFluidUsingCreate() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        try (var propagation = mockStatic(FluidPropagator.class)) {
            propagation.when(() -> FluidPropagator.isOpenEnd(level, BlockPos.ZERO, Direction.EAST)).thenReturn(true);
            assertEquals(500, outlet.push(shell, Direction.EAST, source, 1000));
            verify(level, never()).setBlock(eq(targetPos), any(), anyInt());
            var saved = outlet.write(level.registryAccess());
            ShellFluidOutlet restored = new ShellFluidOutlet();
            restored.read(saved, level.registryAccess(), BlockPos.ZERO);
            assertEquals(0, restored.push(shell, Direction.EAST, source, 0));
            assertEquals(500, restored.push(shell, Direction.EAST, source, 1000));
            verify(level).setBlock(targetPos, Fluids.WATER.defaultFluidState().createLegacyBlock(), 3);
            assertEquals(1000, source.getFluidAmount());
        }
    }

    @Test
    void changingFluidDoesNotDiscardPartialOpenEndBucket() {
        try (var propagation = mockStatic(FluidPropagator.class)) {
            propagation.when(() -> FluidPropagator.isOpenEnd(level, BlockPos.ZERO, Direction.EAST)).thenReturn(true);
            outlet.push(shell, Direction.EAST, source, 1000);
            var saved = outlet.write(level.registryAccess());
            FluidTank lava = new FluidTank(8100);
            lava.fill(new FluidStack(Fluids.LAVA, 2000), EXECUTE);
            assertEquals(0, outlet.push(shell, Direction.EAST, lava, 1000));
            assertEquals(saved, outlet.write(level.registryAccess()));
            assertEquals(2000, lava.getFluidAmount());
        }
    }

    @Test
    void movingShellRetargetsOpenEndWithoutLosingPartialBucket() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        BlockPos movedPos = new BlockPos(20, 0, 0);
        try (var propagation = mockStatic(FluidPropagator.class)) {
            propagation.when(() -> FluidPropagator.isOpenEnd(level, BlockPos.ZERO, Direction.EAST)).thenReturn(true);
            propagation.when(() -> FluidPropagator.isOpenEnd(level, movedPos, Direction.EAST)).thenReturn(true);
            assertEquals(500, outlet.push(shell, Direction.EAST, source, 1000));
            when(shell.getBlockPos()).thenReturn(movedPos);
            assertEquals(500, outlet.push(shell, Direction.EAST, source, 1000));
            verify(level).setBlock(movedPos.east(), Fluids.WATER.defaultFluidState().createLegacyBlock(), 3);
            verify(level, never()).setBlock(eq(targetPos), any(), anyInt());
            assertEquals(1000, source.getFluidAmount());
        }
    }

    @Test
    void distinctFacesKeepSeparatePartialBuckets() {
        try (var propagation = mockStatic(FluidPropagator.class)) {
            propagation.when(() -> FluidPropagator.isOpenEnd(eq(level), eq(BlockPos.ZERO), any(Direction.class)))
                    .thenReturn(true);
            FluidTank lava = new FluidTank(8100);
            lava.fill(new FluidStack(Fluids.LAVA, 2000), EXECUTE);
            assertEquals(500, outlet.push(shell, Direction.EAST, source, 1000));
            assertEquals(500, outlet.push(shell, Direction.WEST, lava, 1000));
            var saved = outlet.write(level.registryAccess());
            FluidStack east = OpenEndedPipe.fromNBT(saved.getCompound("east"), level.registryAccess(), BlockPos.ZERO)
                    .provideHandler().getCapability().getFluidInTank(0);
            FluidStack west = OpenEndedPipe.fromNBT(saved.getCompound("west"), level.registryAccess(), BlockPos.ZERO)
                    .provideHandler().getCapability().getFluidInTank(0);
            assertEquals(500, east.getAmount());
            assertEquals(500, west.getAmount());
            assertEquals(Fluids.WATER, east.getFluid());
            assertEquals(Fluids.LAVA, west.getFluid());
        }
    }
}
