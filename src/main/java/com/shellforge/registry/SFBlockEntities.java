package com.shellforge.registry;

import com.shellforge.ShellForge;
import com.shellforge.block.entity.FluidInterfaceBlockEntity;
import com.shellforge.block.entity.RedstoneInterfaceBlockEntity;
import com.shellforge.block.entity.ShellBlockBlockEntity;
import com.shellforge.block.entity.ShellCoreBlockEntity;
import com.shellforge.block.entity.StressInterfaceBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SFBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ShellForge.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShellCoreBlockEntity>> SHELL_CORE =
            BLOCK_ENTITIES.register("shell_core", () -> BlockEntityType.Builder.of(
                    ShellCoreBlockEntity::new,
                    SFBlocks.SHELL_CORE.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShellBlockBlockEntity>> SHELL_BLOCK =
            BLOCK_ENTITIES.register("shell_block", () -> BlockEntityType.Builder.of(
                    ShellBlockBlockEntity::new,
                    SFBlocks.SHELL_BLOCK.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StressInterfaceBlockEntity>> STRESS_INTERFACE =
            BLOCK_ENTITIES.register("stress_interface", () -> BlockEntityType.Builder.of(
                    StressInterfaceBlockEntity::new,
                    SFBlocks.STRESS_INTERFACE.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneInterfaceBlockEntity>> REDSTONE_INTERFACE =
            BLOCK_ENTITIES.register("redstone_interface", () -> BlockEntityType.Builder.of(
                    RedstoneInterfaceBlockEntity::new,
                    SFBlocks.REDSTONE_INTERFACE.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidInterfaceBlockEntity>> FLUID_INTERFACE =
            BLOCK_ENTITIES.register("fluid_interface", () -> BlockEntityType.Builder.of(
                    FluidInterfaceBlockEntity::new,
                    SFBlocks.FLUID_INTERFACE.get()
            ).build(null));

    private SFBlockEntities() {
    }

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
