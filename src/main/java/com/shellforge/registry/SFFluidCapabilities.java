package com.shellforge.registry;

import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class SFFluidCapabilities {
    private SFFluidCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                SFBlockEntities.FLUID_INTERFACE.get(),
                FluidInterfaceBlockEntity::getFluidHandler
        );
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                SFBlockEntities.SHELL_BLOCK.get(),
                ShellBlockBlockEntity::getFluidHandler
        );
    }
}
