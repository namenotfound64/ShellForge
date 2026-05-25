package com.shellforge.shell;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

public final class CombinedFluidHandler implements IFluidHandler {
    private final List<IFluidHandler> handlers;

    public CombinedFluidHandler(List<IFluidHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    @Override
    public int getTanks() {
        int total = 0;
        for (IFluidHandler handler : handlers) {
            total += handler.getTanks();
        }
        return total;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        int[] resolved = resolveTank(tank);
        return resolved == null ? FluidStack.EMPTY : handlers.get(resolved[0]).getFluidInTank(resolved[1]);
    }

    @Override
    public int getTankCapacity(int tank) {
        int[] resolved = resolveTank(tank);
        return resolved == null ? 0 : handlers.get(resolved[0]).getTankCapacity(resolved[1]);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        int[] resolved = resolveTank(tank);
        return resolved != null && handlers.get(resolved[0]).isFluidValid(resolved[1], stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        int filled = 0;
        FluidStack remaining = resource.copy();
        for (IFluidHandler handler : handlers) {
            int amount = handler.fill(remaining, action);
            if (amount > 0) {
                filled += amount;
                remaining.shrink(amount);
                if (remaining.isEmpty()) {
                    break;
                }
            }
        }
        return filled;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        for (IFluidHandler handler : handlers) {
            FluidStack drained = handler.drain(resource, action);
            if (!drained.isEmpty()) {
                return drained;
            }
        }
        return FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        for (IFluidHandler handler : handlers) {
            FluidStack drained = handler.drain(maxDrain, action);
            if (!drained.isEmpty()) {
                return drained;
            }
        }
        return FluidStack.EMPTY;
    }

    private @org.jetbrains.annotations.Nullable int[] resolveTank(int globalTank) {
        int offset = 0;
        for (int handlerIndex = 0; handlerIndex < handlers.size(); handlerIndex++) {
            IFluidHandler handler = handlers.get(handlerIndex);
            int tankCount = handler.getTanks();
            if (globalTank < offset + tankCount) {
                return new int[]{handlerIndex, globalTank - offset};
            }
            offset += tankCount;
        }
        return null;
    }

    public static @org.jetbrains.annotations.Nullable IFluidHandler merge(List<IFluidHandler> handlers) {
        List<IFluidHandler> valid = new ArrayList<>();
        for (IFluidHandler handler : handlers) {
            if (handler != null) {
                valid.add(handler);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        if (valid.size() == 1) {
            return valid.getFirst();
        }
        return new CombinedFluidHandler(valid);
    }
}
