package com.shellforge.registry;

import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.content.ShellFluidFacePos;
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
        event.registerBlock(
                Capabilities.FluidHandler.BLOCK,
                (level, pos, state, blockEntity, side) -> {
                    if (blockEntity instanceof ShellBlockBlockEntity shellBlock) {
                        return pos instanceof ShellFluidFacePos facePos
                                ? shellBlock.getFluidFaceHandler(facePos.face(), side)
                                : shellBlock.getFluidHandler(side);
                    }
                    return null;
                },
                SFBlocks.SHELL_BLOCK.get()
        );
    }
}
