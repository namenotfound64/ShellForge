package com.shellforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.shellforge.item.ShellBlueprintItem;
import com.shellforge.shell.ShellCopyException;
import com.shellforge.shell.ShellDebugSettings;
import com.shellforge.shell.ShellDefinition;
import com.shellforge.shell.ShellEndpoint;
import com.shellforge.shell.ShellEndpointType;
import com.shellforge.shell.ShellManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShellForgeCommand {
    private static final SimpleCommandExceptionType INVALID_ENDPOINT_TYPE =
            new SimpleCommandExceptionType(Component.translatable("command.shellforge.copy.invalid_type"));
    private static final Pattern FACE_FLAG_PATTERN = Pattern.compile(
            "--(?<face>north|south|east|west|up|down)\\s+(?<type>stress|redstone|fluid)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OUTPUT_POS_PATTERN = Pattern.compile(
            "--outputPos\\s+(?<x>-?\\d+)\\s+(?<y>-?\\d+)\\s+(?<z>-?\\d+)"
    );

    private ShellForgeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shellforge")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on")
                                .executes(ctx -> setDebug(ctx, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setDebug(ctx, false)))
                        .executes(ctx -> {
                            boolean enabled = ShellDebugSettings.isEnabled();
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "command.shellforge.debug.status",
                                    enabled
                            ), false);
                            return 1;
                        }))
                .then(Commands.literal("copy")
                        .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                        .executes(ctx -> executeCopy(ctx, Map.of(), null))
                                        .then(Commands.argument("faceFlags", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    String raw = StringArgumentType.getString(ctx, "faceFlags");
                                                    return executeCopy(ctx, parseFaceFlags(raw), parseOutputPos(raw));
                                                }))))));
    }

    private static int setDebug(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        ShellDebugSettings.setEnabled(enabled);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                enabled
                        ? "command.shellforge.debug.enabled"
                        : "command.shellforge.debug.disabled"
        ), true);
        return 1;
    }

    private static int executeCopy(
            CommandContext<CommandSourceStack> ctx,
            Map<Direction, List<ShellEndpointType>> faceOverrides,
            @Nullable BlockPos outputPos
    ) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos1 = BlockPosArgument.getLoadedBlockPos(ctx, "pos1");
        BlockPos pos2 = BlockPosArgument.getLoadedBlockPos(ctx, "pos2");

        ShellDefinition definition;
        try {
            definition = ShellManager.createDefinition(level, pos1, pos2, Map.copyOf(faceOverrides));
        } catch (ShellCopyException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }

        if (definition.isEmpty()) {
            source.sendFailure(Component.translatable("command.shellforge.copy.empty"));
            return 0;
        }

        ServerSubLevel templateSubLevel = ShellManager.packSourceToTemplateSubLevel(level, pos1, pos2);

        ServerPlayer player = source.getPlayerOrException();
        ItemStack blueprint = ShellBlueprintItem.createStack(definition);
        if (!player.getInventory().add(blueprint)) {
            player.drop(blueprint, false);
        }

        source.sendSuccess(() -> Component.translatable(
                "command.shellforge.copy.success",
                definition.blocks().size(),
                definition.endpoints().size(),
                templateSubLevel.getUniqueId().toString()
        ), true);

        if (outputPos != null) {
            source.sendSuccess(() -> Component.translatable(
                    "command.shellforge.copy.output_hint",
                    outputPos.toShortString()
            ), false);
        }

        for (ShellEndpoint endpoint : definition.endpoints()) {
            source.sendSuccess(() -> Component.literal(
                    endpoint.shellFace().getName().toUpperCase(Locale.ROOT)
                            + " " + endpoint.type()
                            + " internal=@" + endpoint.internalInterfacePos().toShortString()
                            + " id=" + endpoint.id()
            ), false);
        }
        return definition.endpoints().size();
    }

    private static Map<Direction, List<ShellEndpointType>> parseFaceFlags(String raw) throws CommandSyntaxException {
        EnumMap<Direction, List<ShellEndpointType>> overrides = new EnumMap<>(Direction.class);
        Matcher matcher = FACE_FLAG_PATTERN.matcher(raw);
        while (matcher.find()) {
            Direction face = parseDirection(matcher.group("face"));
            ShellEndpointType type = parseEndpointType(matcher.group("type"));
            overrides.computeIfAbsent(face, ignored -> new ArrayList<>()).add(type);
        }
        return overrides;
    }

    private static @Nullable BlockPos parseOutputPos(String raw) {
        Matcher matcher = OUTPUT_POS_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        return new BlockPos(
                Integer.parseInt(matcher.group("x")),
                Integer.parseInt(matcher.group("y")),
                Integer.parseInt(matcher.group("z"))
        );
    }

    private static Direction parseDirection(String raw) {
        return Direction.byName(raw.toLowerCase(Locale.ROOT));
    }

    private static ShellEndpointType parseEndpointType(String raw) throws CommandSyntaxException {
        try {
            return ShellEndpointType.byName(raw);
        } catch (IllegalArgumentException exception) {
            throw INVALID_ENDPOINT_TYPE.create();
        }
    }
}
