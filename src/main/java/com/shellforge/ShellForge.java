package com.shellforge;

import com.mojang.logging.LogUtils;
import com.shellforge.command.ShellForgeCommand;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.registry.SFBlocks;
import com.shellforge.registry.SFCreativeTabs;
import com.shellforge.registry.SFFluidCapabilities;
import com.shellforge.registry.SFItems;
import com.shellforge.shell.ShellManager;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * ShellForge (mod id {@value #MODID}) — compress structures into shell blocks backed by Sable sublevels.
 * Display name: ShellForge / 集成传动. See {@code memory/project_architecture.md} for system overview.
 */
@Mod(ShellForge.MODID)
public final class ShellForge {
    public static final String MODID = "shellforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ShellForge(IEventBus modEventBus) {
        SFBlocks.register(modEventBus);
        SFItems.register(modEventBus);
        SFBlockEntities.register(modEventBus);
        SFCreativeTabs.register(modEventBus);

        modEventBus.addListener(SFFluidCapabilities::register);
        NeoForge.EVENT_BUS.register(this);
        SableEventPlatform.INSTANCE.onSubLevelContainerReady(ShellManager::onSubLevelContainerReady);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ShellForgeCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ShellManager.schedulePostLoadRebindSequence();
    }

    @SubscribeEvent
    public void onServerPostTick(ServerTickEvent.Post event) {
        ShellManager.serverTick(event.getServer());
    }
}
