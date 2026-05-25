package com.shellforge.mixin;

import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IExtraFaceKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Resolves {@link FaceExtraBlockPos} to the per-face hub on an {@link IExtraFaceKinetics} parent.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void handleFaceExtraBlockPos(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (pos instanceof FaceExtraBlockPos facePos) {
            BlockEntity be = ((Level) (Object) this).getBlockEntity(new BlockPos(facePos));
            if (be instanceof IExtraFaceKinetics shellBlock) {
                cir.setReturnValue(shellBlock.getFaceKinetics(facePos.face()));
            } else {
                cir.setReturnValue(null);
            }
        }
    }
}
