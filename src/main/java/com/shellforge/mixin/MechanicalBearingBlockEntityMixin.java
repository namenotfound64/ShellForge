package com.shellforge.mixin;

import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create resets {@code sequencedAngleLimit} on every {@code onSpeedChanged}. Shell stress bridging causes
 * frequent network speed updates; ignoring no-op speed changes during an active turn prevents the bearing
 * from re-acquiring a full angle quota and overshooting (slow client correction afterward).
 */
@Mixin(value = MechanicalBearingBlockEntity.class, remap = false)
public abstract class MechanicalBearingBlockEntityMixin {

    private static final float SPEED_EPSILON = 1e-3f;

    @Shadow
    protected double sequencedAngleLimit;

    @Inject(method = "onSpeedChanged", at = @At("HEAD"), cancellable = true)
    private void shellforge$ignoreSpuriousSpeedUpdatesDuringSequence(float previousSpeed, CallbackInfo ci) {
        MechanicalBearingBlockEntity self = (MechanicalBearingBlockEntity) (Object) this;
        if (self.sequenceContext == null
                || self.sequenceContext.instruction() != SequencerInstructions.TURN_ANGLE
                || sequencedAngleLimit < 0) {
            return;
        }
        float speed = self.getSpeed();
        if (Math.abs(previousSpeed - speed) < SPEED_EPSILON
                && (previousSpeed == 0 || Math.signum(previousSpeed) == Math.signum(speed))) {
            ci.cancel();
        }
    }
}
