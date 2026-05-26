package com.shellforge.mixin;

import com.shellforge.shell.ShellKineticAngleSync;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create only propagates RPM through kinetic networks; rotation phase stays local to each block.
 * For continuous rotation (no {@code sequenceContext}), relay shaft phase through the shell hub.
 * Angle-based devices (springs, sequenced gearshifts) use {@code sequenceContext} instead — do not
 * override phase for those networks.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticBlockEntityMixin {

    @Inject(method = "getRotationAngleOffset", at = @At("HEAD"), cancellable = true)
    private void shellforge$inheritBridgedVisualAngle(Direction.Axis axis, CallbackInfoReturnable<Integer> cir) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (!ShellKineticAngleSync.shouldApplyShaftPhaseSync(self, axis)) {
            return;
        }
        Float visualAngle = ShellKineticAngleSync.findInheritedVisualAngle(self, axis);
        if (visualAngle != null) {
            cir.setReturnValue(ShellKineticAngleSync.rotationOffsetForVisualAngle(self, axis, visualAngle));
        }
    }
}
