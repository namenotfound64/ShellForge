package com.shellforge.item;

import com.shellforge.shell.ShellDebug;
import com.shellforge.shell.ShellDebugSettings;
import com.shellforge.shell.ShellDefinition;
import com.shellforge.shell.ShellInstance;
import com.shellforge.shell.ShellManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import org.jetbrains.annotations.Nullable;

public class ShellBlueprintItem extends Item {
    private static final String DEFINITION_KEY = "ShellDefinition";

    public ShellBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        ShellDefinition definition = getDefinition(stack, level);
        if (definition == null || definition.isEmpty()) {
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.translatable("command.shellforge.copy.no_definition"), true);
            }
            return InteractionResult.FAIL;
        }

        BlockPos origin = context.getClickedPos().relative(context.getClickedFace());
        ShellInstance instance = ShellManager.instantiate(level, definition, origin);
        if (context.getPlayer() instanceof ServerPlayer player && ShellDebugSettings.isEnabled()) {
            ShellDebug.logPlacementPositions(player, level, definition, instance);
        }
        if (context.getPlayer() != null) {
            context.getPlayer().displayClientMessage(
                    Component.translatable("command.shellforge.copy.placed", instance.bindings().size(), instance.subLevelId().toString()),
                    false
            );
            if (!context.getPlayer().getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return InteractionResult.CONSUME;
    }

    public static ItemStack createStack(ShellDefinition definition) {
        ItemStack stack = new ItemStack(com.shellforge.registry.SFItems.SHELL_BLUEPRINT.get());
        CompoundTag root = new CompoundTag();
        root.put(DEFINITION_KEY, definition.save());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    public static @Nullable ShellDefinition getDefinition(ItemStack stack, ServerLevel level) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }
        CompoundTag root = customData.copyTag();
        if (!root.contains(DEFINITION_KEY)) {
            return null;
        }
        return ShellDefinition.load(root.getCompound(DEFINITION_KEY), level.registryAccess());
    }
}
