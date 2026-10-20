package com.shellforge.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * Marker {@link BlockPos} for a per-face fluid endpoint hosted by a shell block.
 */
public class ShellFluidFacePos extends BlockPos {
    private final Direction face;

    public ShellFluidFacePos(Vec3i shellPos, Direction face) {
        super(shellPos.getX(), shellPos.getY(), shellPos.getZ());
        this.face = face;
    }

    public Direction face() {
        return face;
    }
}
