package com.shellforge.block;

import com.shellforge.block.entity.RedstoneInterfaceBlockEntity;
import com.shellforge.registry.SFBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension;
import org.jetbrains.annotations.Nullable;

/**
 * Directional redstone port. {@link #FACING} points toward the bound {@code shell_block} face.
 * When externally powered through the shell, acts as a strongly powered block (like a lever-powered
 * solid). When strongly powered, emits signal 15 only on {@link #FACING} (observer-style output face).
 */
public class RedstoneInterfaceBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public RedstoneInterfaceBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * Weakly power adjacent redstone in the sublevel when the shell injects power.
     * This makes nearby dust/lamps behave like they are touching a lever-powered block.
     */
    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof RedstoneInterfaceBlockEntity redstone && redstone.isStronglyPowered()) {
            return 15;
        }
        return 0;
    }

    /**
     * Strong power into adjacent blocks in the sublevel (lever-powered opaque behavior).
     */
    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof RedstoneInterfaceBlockEntity redstone && redstone.isStronglyPowered()) {
            return 15;
        }
        return 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RedstoneInterfaceBlockEntity(pos, state);
    }
}