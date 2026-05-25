package com.shellforge.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rewrites a neighbour lookup through a multi-connection block (e.g. shell) to the real endpoint,
 * such as a {@link FaceExtraBlockPos} stress hub.
 */
public interface IConnectionForwardingBlock {
    BlockPos forwardConnection(Level level, BlockPos sourcePos, BlockState sourceState, BlockPos neighborPos);
}
