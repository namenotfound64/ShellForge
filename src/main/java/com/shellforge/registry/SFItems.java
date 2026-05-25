package com.shellforge.registry;

import com.shellforge.ShellForge;
import com.shellforge.item.ShellBlueprintItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SFItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ShellForge.MODID);

    public static final DeferredItem<BlockItem> SHELL_CORE = ITEMS.registerSimpleBlockItem(
            "shell_core",
            SFBlocks.SHELL_CORE
    );

    public static final DeferredItem<BlockItem> SHELL_BLOCK = ITEMS.registerSimpleBlockItem(
            "shell_block",
            SFBlocks.SHELL_BLOCK
    );

    public static final DeferredItem<BlockItem> FACE_MARKER = ITEMS.registerSimpleBlockItem(
            "face_marker",
            SFBlocks.FACE_MARKER
    );

    public static final DeferredItem<BlockItem> STRESS_INTERFACE = ITEMS.registerSimpleBlockItem(
            "stress_interface",
            SFBlocks.STRESS_INTERFACE
    );

    public static final DeferredItem<BlockItem> REDSTONE_INTERFACE = ITEMS.registerSimpleBlockItem(
            "redstone_interface",
            SFBlocks.REDSTONE_INTERFACE
    );

    public static final DeferredItem<BlockItem> FLUID_INTERFACE = ITEMS.registerSimpleBlockItem(
            "fluid_interface",
            SFBlocks.FLUID_INTERFACE
    );

    public static final DeferredItem<ShellBlueprintItem> SHELL_BLUEPRINT = ITEMS.register(
            "shell_blueprint",
            () -> new ShellBlueprintItem(new Item.Properties().stacksTo(64))
    );

    private SFItems() {
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
