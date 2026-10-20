package com.shellforge.shell;

import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Buffer tank at a fluid interface. Create pipes treat blocks with {@link IFluidHandler} as
 * endpoints ({@code FlowSource.FluidHandler}); adjacent interior/exterior pipe networks fill and
 * drain this tank through the normal Create fluid network, without touching pipe flow state.
 */
public final class CreatePipeBridgingFluidHandler implements IFluidHandler {
    private static final int CAPACITY = 8100;

    private final SmartFluidTank tank;

    public CreatePipeBridgingFluidHandler(FluidInterfaceBlockEntity blockEntity) {
        this.tank = new SmartFluidTank(CAPACITY, stack -> blockEntity.onBridgeTankChanged());
    }

    public SmartFluidTank tank() {
        return tank;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return this.tank.getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
        return this.tank.getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return true;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        return this.tank.fill(resource, action);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        return this.tank.drain(resource, action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        return this.tank.drain(maxDrain, action);
    }
}
