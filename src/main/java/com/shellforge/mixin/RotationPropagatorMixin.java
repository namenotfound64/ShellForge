package com.shellforge.mixin;

import com.shellforge.block.ShellBlock;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IConnectionForwardingBlock;
import com.shellforge.content.ShellKineticLinkPos;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Create {@link RotationPropagator} hooks for shell / per-face hub topology.
 * <ul>
 *   <li>Parent shell: no external neighbour positions (hubs connect outward only).</li>
 *   <li>Hub: single neighbour on its bound face; gearshift sign conflicts must not destroy the shell block.</li>
 *   <li>Forwarding: walk {@link IConnectionForwardingBlock} chains and append {@link FaceExtraBlockPos} for stress faces.</li>
 * </ul>
 */
@Mixin(RotationPropagator.class)
public abstract class RotationPropagatorMixin {

    @ModifyVariable(
            method = "propagateNewSource",
            at = @At(value = "STORE"),
            ordinal = 0,
            remap = false
    )
    private static boolean ignoreIncompatibleSignForShellHub(boolean incompatible, KineticBlockEntity currentTE) {
        if (incompatible && currentTE instanceof ShellBlockBlockEntity.PerFaceStressHub) {
            return false;
        }
        return incompatible;
    }

    @Redirect(
            method = "propagateNewSource",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
            remap = true
    )
    private static boolean skipDestroyShellStressHub(
            Level world,
            BlockPos pos,
            boolean dropBlock,
            KineticBlockEntity currentTE
    ) {
        if (currentTE instanceof ShellBlockBlockEntity.PerFaceStressHub) {
            return false;
        }
        return world.destroyBlock(pos, dropBlock);
    }

    @Inject(
            method = "getAxisModifier",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void getSplitShaftModifier(KineticBlockEntity be, Direction direction, CallbackInfoReturnable<Float> cir) {
        if (be instanceof ShellBlockBlockEntity.PerFaceStressHub) {
            cir.setReturnValue(1f);
            return;
        }

        if (!(be.hasSource() || be.isSource())) {
            return;
        }

        if (be instanceof ShellBlockBlockEntity shellBlock) {
            cir.setReturnValue(shellBlock.getRotationSpeedModifier(direction));
        }
    }

    @Inject(
            method = "getPotentialNeighbourLocations",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void blockShellParentNeighbors(KineticBlockEntity be, CallbackInfoReturnable<List<BlockPos>> cir) {
        if (be instanceof ShellBlockBlockEntity) {
            cir.setReturnValue(List.of());
            return;
        }

        if (be instanceof ShellBlockBlockEntity.PerFaceStressHub hub) {
            List<BlockPos> neighbours = new ArrayList<>();
            neighbours.add(hub.getParentBlockEntity().getBlockPos().relative(hub.getFace()));
            neighbours.add(new ShellKineticLinkPos(hub.getParentBlockEntity().getBlockPos(), hub.getFace()));
            cir.setReturnValue(neighbours);
        }
    }

    @Inject(
            method = "getPotentialNeighbourLocations",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void forwardConnection(KineticBlockEntity be, CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> positions = new ArrayList<>(cir.getReturnValue());
        for (int i = 0; i < positions.size(); i++) {
            BlockPos sourcePos = be.getBlockPos();
            BlockPos neighborPos = positions.get(i);

            while (!sourcePos.equals(neighborPos)
                    && be.getLevel() != null
                    && be.getLevel().getBlockState(neighborPos).getBlock() instanceof IConnectionForwardingBlock forwardingBlock) {
                BlockPos tempSource = sourcePos;
                sourcePos = neighborPos;
                neighborPos = forwardingBlock.forwardConnection(
                        be.getLevel(), tempSource,
                        be.getLevel().getBlockState(tempSource), neighborPos);
            }

            positions.set(i, neighborPos);
        }

        if (be.getLevel() != null) {
            for (BlockPos pos : new ArrayList<>(positions)) {
                if (!(be.getLevel().getBlockState(pos).getBlock() instanceof ShellBlock)) {
                    continue;
                }
                BlockEntity blockEntity = be.getLevel().getBlockEntity(pos);
                if (!(blockEntity instanceof ShellBlockBlockEntity shellBlock)) {
                    continue;
                }
                for (Direction direction : Direction.values()) {
                    if (shellBlock.hasStressFace(direction)) {
                        FaceExtraBlockPos extraPos = new FaceExtraBlockPos(pos, direction);
                        if (!positions.contains(extraPos)) {
                            positions.add(extraPos);
                        }
                    }
                }
            }
        }

        cir.setReturnValue(positions);
    }
}
