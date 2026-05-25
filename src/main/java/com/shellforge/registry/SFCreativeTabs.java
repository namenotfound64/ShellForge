package com.shellforge.registry;

import com.shellforge.ShellForge;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SFCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ShellForge.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.shellforge"))
                    .withTabsBefore(CreativeModeTabs.REDSTONE_BLOCKS)
                    .icon(() -> SFItems.SHELL_BLUEPRINT.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(SFItems.SHELL_BLUEPRINT.get());
                        output.accept(SFItems.SHELL_BLOCK.get());
                        output.accept(SFItems.FACE_MARKER.get());
                        output.accept(SFItems.STRESS_INTERFACE.get());
                        output.accept(SFItems.REDSTONE_INTERFACE.get());
                        output.accept(SFItems.FLUID_INTERFACE.get());
                        output.accept(SFItems.SHELL_CORE.get());
                    })
                    .build()
    );

    private SFCreativeTabs() {
    }

    public static void register(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}
