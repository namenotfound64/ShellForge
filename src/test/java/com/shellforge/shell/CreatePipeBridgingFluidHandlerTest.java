package com.shellforge.shell;

import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreatePipeBridgingFluidHandlerTest {
    @Test
    void simulationDoesNotMutateOrNotifyAndMixedFluidsAreRejected() {
        FluidInterfaceBlockEntity owner = mock(FluidInterfaceBlockEntity.class);
        CreatePipeBridgingFluidHandler handler = new CreatePipeBridgingFluidHandler(owner);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        assertEquals(1000, handler.fill(water, SIMULATE));
        assertTrue(handler.getFluidInTank(0).isEmpty());
        verifyNoInteractions(owner);
        assertEquals(1000, handler.fill(water, EXECUTE));
        assertEquals(0, handler.fill(new FluidStack(Fluids.LAVA, 1000), EXECUTE));
        assertEquals(400, handler.drain(400, SIMULATE).getAmount());
        assertEquals(1000, handler.getFluidInTank(0).getAmount());
        verify(owner, times(1)).onBridgeTankChanged();
        assertEquals(400, handler.drain(400, EXECUTE).getAmount());
        assertEquals(600, handler.getFluidInTank(0).getAmount());
        verify(owner, times(2)).onBridgeTankChanged();
    }

    @Test
    void contentsChangedOnlyMarksTheInterfaceDirty() {
        FluidInterfaceBlockEntity owner = mock(FluidInterfaceBlockEntity.class);
        doCallRealMethod().when(owner).onBridgeTankChanged();
        owner.onBridgeTankChanged();
        verify(owner).setChanged();
        verify(owner).onBridgeTankChanged();
        verifyNoMoreInteractions(owner);
    }
}
