package com.shellforge.mixin;

import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IExtraFaceKinetics;
import com.shellforge.content.ShellKineticLinkPos;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.block.entity.StressInterfaceBlockEntity;
import com.shellforge.shell.ShellManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
            return;
        }

        if (pos instanceof ShellKineticLinkPos linkPos && ((Level) (Object) this) instanceof ServerLevel serverLevel) {
            BlockEntity shell = serverLevel.getBlockEntity(new BlockPos(linkPos));
            if (shell instanceof ShellBlockBlockEntity shellBlock) {
                cir.setReturnValue(shellBlock.findStressInterfaceOnFace(serverLevel, linkPos.face()));
            } else {
                cir.setReturnValue(null);
            }
        }
    }
}
