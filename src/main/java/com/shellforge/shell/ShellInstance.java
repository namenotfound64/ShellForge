package com.shellforge.shell;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/** Runtime link between a placed {@link com.shellforge.block.ShellBlock} and its sublevel copy. */
public record ShellInstance(
        UUID shellId,
        UUID definitionId,
        UUID subLevelId,
        BlockPos origin,
        BlockPos structureWorldOrigin,
        /** Plot-local copy volume (same coords as {@link ShellBlockSnapshot#relativePos()}). */
        BlockPos structureSize,
        List<ShellBinding> bindings
) {
}
