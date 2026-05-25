package com.shellforge.mixin;

import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IExtraFaceKinetics;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ensures {@link KineticNetwork} stress/capacity aggregation sees hubs at {@link FaceExtraBlockPos}.
 * <p>
 * If Simulated is on the classpath at runtime, its {@code extra_kinetics.KineticNetworkMixin} may win
 * the redirect slot instead; hubs still work when only ShellForge is loaded.
 */
@Mixin(KineticNetwork.class)
public abstract class KineticNetworkMixin {

    @Redirect(
            method = "calculateCapacity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;")
    )
    private BlockEntity resolveFaceExtraForCapacity(Level level, BlockPos pos) {
        return resolveFaceExtra(level, pos);
    }

    @Redirect(
            method = "calculateStress",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;")
    )
    private BlockEntity resolveFaceExtraForStress(Level level, BlockPos pos) {
        return resolveFaceExtra(level, pos);
    }

    private static BlockEntity resolveFaceExtra(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof IExtraFaceKinetics extra && pos instanceof FaceExtraBlockPos facePos) {
            return extra.getFaceKinetics(facePos.face());
        }
        return be;
    }
}
