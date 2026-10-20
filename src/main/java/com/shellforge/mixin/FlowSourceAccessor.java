package com.shellforge.mixin;

import net.createmod.catnip.math.BlockFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(com.simibubi.create.content.fluids.FlowSource.class)
public interface FlowSourceAccessor {
    @Accessor("location")
    BlockFace shellforge$getLocation();
}
