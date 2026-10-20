package com.shellforge.mixin;

import com.shellforge.shell.ShellFluidPressureBridge;
import com.simibubi.create.content.fluids.PipeConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PipeConnection.class)
public abstract class PipeConnectionMixin {
    @Inject(method = "wipePressure", at = @At("HEAD"))
    private void shellforge$forgetMirroredPressure(CallbackInfo ci) {
        ShellFluidPressureBridge.onPressureWiped((PipeConnection) (Object) this);
    }
}
