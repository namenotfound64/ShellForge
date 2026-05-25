package com.shellforge.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * Marker {@link BlockPos} for a per-face kinetic hub at the same XYZ as its parent shell.
 * {@link com.shellforge.mixin.LevelMixin} resolves this to {@link IExtraFaceKinetics#getFaceKinetics}.
 */
public class FaceExtraBlockPos extends BlockPos {
    private final Direction face;

    public FaceExtraBlockPos(Vec3i pos, Direction face) {
        super(pos.getX(), pos.getY(), pos.getZ());
        this.face = face;
    }

    public Direction face() {
        return face;
    }
}
