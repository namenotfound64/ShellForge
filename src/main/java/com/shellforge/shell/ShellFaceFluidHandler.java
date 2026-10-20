package com.shellforge.shell;

import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable {@link IFluidHandler} for one shell exterior face. Create pipes cache this instance via
 * {@code BlockCapabilityCache}; it delegates into the bound sublevel on the server when available.
 */
public final class ShellFaceFluidHandler implements IFluidHandler {
    private static final int FALLBACK_CAPACITY = 8100;

    private final ShellBlockBlockEntity shell;
    private final Direction shellFace;

    public ShellFaceFluidHandler(ShellBlockBlockEntity shell, Direction shellFace) {
        this.shell = shell;
        this.shellFace = shellFace;
    }

    private @Nullable IFluidHandler resolveDelegate() {
        Level level = shell.getLevel();
        if (shell.isRemoved() || level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        ShellInstance instance = shell.getInstance();
        if (instance == null) {
            return null;
        }
        if (ShellManager.resolveSubLevel(serverLevel, instance.subLevelId(), shell.getBlockPos()) == null) {
            return null;
        }
        List<IFluidHandler> handlers = new ArrayList<>();
        for (ShellBinding binding : shell.getBindings(shellFace)) {
            if (binding.type() != ShellEndpointType.FLUID) {
                continue;
            }
            BlockEntity internal = ShellManager.getInternalBlockEntity(
                    serverLevel,
                    instance.subLevelId(),
                    binding.internalInterfacePos(),
                    shell.getBlockPos()
            );
            if (internal instanceof FluidInterfaceBlockEntity fluidInterface && !fluidInterface.isRemoved()) {
                IFluidHandler handler = fluidInterface.getFluidHandler(binding.shellFace().getOpposite());
                if (handler != null) {
                    handlers.add(handler);
                }
            }
        }
        if (handlers.isEmpty()) {
            return null;
        }
        if (handlers.size() == 1) {
            return handlers.getFirst();
        }
        return new CombinedTankWrapper(handlers.toArray(IFluidHandler[]::new));
    }

    @Override
    public int getTanks() {
        IFluidHandler delegate = resolveDelegate();
        return delegate != null ? delegate.getTanks() : 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        IFluidHandler delegate = resolveDelegate();
        return delegate != null ? delegate.getFluidInTank(tank) : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        IFluidHandler delegate = resolveDelegate();
        if (delegate != null) {
            return delegate.getTankCapacity(tank);
        }
        return shell.getBindings(shellFace).stream().anyMatch(b -> b.type() == ShellEndpointType.FLUID)
                ? FALLBACK_CAPACITY
                : 0;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        IFluidHandler delegate = resolveDelegate();
        return delegate == null || delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        IFluidHandler delegate = resolveDelegate();
        return delegate != null ? delegate.fill(resource, action) : 0;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        IFluidHandler delegate = resolveDelegate();
        return delegate != null ? delegate.drain(resource, action) : FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        IFluidHandler delegate = resolveDelegate();
        return delegate != null ? delegate.drain(maxDrain, action) : FluidStack.EMPTY;
    }
}
