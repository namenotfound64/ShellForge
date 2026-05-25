package com.shellforge.registry;

import com.shellforge.ShellForge;
import com.shellforge.block.FaceMarkerBlock;
import com.shellforge.block.FluidInterfaceBlock;
import com.shellforge.block.RedstoneInterfaceBlock;
import com.shellforge.block.ShellBlock;
import com.shellforge.block.ShellCoreBlock;
import com.shellforge.block.StressInterfaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SFBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ShellForge.MODID);

    public static final DeferredBlock<ShellCoreBlock> SHELL_CORE = BLOCKS.register(
            "shell_core",
            () -> new ShellCoreBlock(commonProperties().strength(4.0F))
    );

    public static final DeferredBlock<ShellBlock> SHELL_BLOCK = BLOCKS.register(
            "shell_block",
            () -> new ShellBlock(shellBlockProperties())
    );

    public static final DeferredBlock<FaceMarkerBlock> FACE_MARKER = BLOCKS.register(
            "face_marker",
            () -> new FaceMarkerBlock(commonProperties().strength(0.5F).noOcclusion())
    );

    public static final DeferredBlock<StressInterfaceBlock> STRESS_INTERFACE = BLOCKS.register(
            "stress_interface",
            () -> new StressInterfaceBlock(commonProperties().strength(3.0F))
    );

    public static final DeferredBlock<RedstoneInterfaceBlock> REDSTONE_INTERFACE = BLOCKS.register(
            "redstone_interface",
            () -> new RedstoneInterfaceBlock(commonProperties().strength(2.0F))
    );

    public static final DeferredBlock<FluidInterfaceBlock> FLUID_INTERFACE = BLOCKS.register(
            "fluid_interface",
            () -> new FluidInterfaceBlock(commonProperties().strength(2.5F))
    );

    private SFBlocks() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }

    private static BlockBehaviour.Properties commonProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties shellBlockProperties() {
        return commonProperties()
                .strength(4.0F)
                .isRedstoneConductor((state, level, pos) -> false);
    }
}
