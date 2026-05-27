package com.shellforge.block;

import com.mojang.serialization.MapCodec;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.content.FaceExtraBlockPos;
import com.shellforge.content.IConnectionForwardingBlock;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.ShellInstance;
import com.shellforge.shell.ShellManager;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

import org.jetbrains.annotations.Nullable;

/**
 * Main-world shell block: passthrough parent BE plus per-face stress hubs (see {@link ShellBlockBlockEntity}).
 * Redstone and fluid are bridged in the block entity; Create shaft connectivity is per stress binding face.
 */
public class ShellBlock extends KineticBlock implements EntityBlock, IConnectionForwardingBlock, BlockSubLevelAssemblyListener {
    public static final MapCodec<ShellBlock> CODEC = simpleCodec(ShellBlock::new);

    public ShellBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState());
    }

    @Override
    protected MapCodec<? extends KineticBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        if (pos instanceof FaceExtraBlockPos facePos) {
            return facePos.face() == face;
        }

        if (world == null) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof ShellBlockBlockEntity.PerFaceStressHub hub) {
            return hub.getFace() == face;
        }
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock) {
            return shellBlock.hasStressFace(face);
        }
        return false;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public BlockPos forwardConnection(Level level, BlockPos sourcePos, BlockState sourceState, BlockPos neighborPos) {
        BlockState state = level.getBlockState(neighborPos);
        if (state.getBlock() != this)
            return neighborPos;

        Vec3i offset = sourcePos.subtract(neighborPos);
        Direction face = Direction.fromDelta(offset.getX(), offset.getY(), offset.getZ());
        if (face == null)
            return neighborPos;

        BlockEntity blockEntity = level.getBlockEntity(neighborPos);
        if (!(blockEntity instanceof ShellBlockBlockEntity shellBlock))
            return neighborPos;

        if (shellBlock.hasStressFace(face)) {
            return new FaceExtraBlockPos(neighborPos, face);
        }

        return neighborPos;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction) {
        return false;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock) {
            return shellBlock.getRedstoneOutput(direction.getOpposite());
        }
        return 0;
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !movedByPiston && !state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ShellBlockBlockEntity shellBlock) {
                ShellInstance instance = shellBlock.getInstance();
                if (instance != null) {
                    ShellManager.destroyInstance((ServerLevel) level, instance);
                    shellBlock.clearInstance();
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (level.getBlockEntity(pos) instanceof ShellBlockBlockEntity shellBlock) {
            shellBlock.onNeighborChanged(fromPos);
        }
    }

    @Override
    public void afterMove(
            ServerLevel originLevel,
            ServerLevel resultingLevel,
            BlockState newState,
            BlockPos oldPos,
            BlockPos newPos
    ) {
        BlockEntity blockEntity = resultingLevel.getBlockEntity(newPos);
        if (blockEntity instanceof ShellBlockBlockEntity shellBlock) {
            shellBlock.requestRebindAfterLoad();
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShellBlockBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == SFBlockEntities.SHELL_BLOCK.get() ? (tickerLevel, tickerPos, tickerState, blockEntity) ->
                ShellBlockBlockEntity.tick(tickerLevel, tickerPos, tickerState, (ShellBlockBlockEntity) blockEntity) : null;
    }
}
