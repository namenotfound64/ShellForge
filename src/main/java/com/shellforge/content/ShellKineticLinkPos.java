package com.shellforge.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * Marker {@link BlockPos} for a per-face shell hub's kinetic link to an internal
 * {@link com.shellforge.block.entity.StressInterfaceBlockEntity}.
 * {@link com.shellforge.mixin.LevelMixin} resolves this via the parent shell block entity.
 */
public class ShellKineticLinkPos extends BlockPos {
    private final Direction face;

    public ShellKineticLinkPos(Vec3i shellPos, Direction face) {
        super(shellPos.getX(), shellPos.getY(), shellPos.getZ());
        this.face = face;
    }

    public Direction face() {
        return face;
    }
}
