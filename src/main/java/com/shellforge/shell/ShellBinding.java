package com.shellforge.shell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Maps one shell face in the main world to an interface block inside a sublevel. */
public record ShellBinding(
        UUID shellId,
        UUID subLevelId,
        String id,
        ShellEndpointType type,
        Direction shellFace,
        BlockPos internalInterfacePos
) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("shellId", shellId);
        tag.putUUID("subLevelId", subLevelId);
        tag.putString("id", id);
        tag.putString("type", type.name());
        tag.putString("shellFace", shellFace.getName());
        tag.putLong("internalInterfacePos", internalInterfacePos.asLong());
        return tag;
    }

    public static ShellBinding load(CompoundTag tag) {
        Direction shellFace = tag.contains("shellFace")
                ? Direction.byName(tag.getString("shellFace"))
                : Direction.byName(tag.getString("face"));
        BlockPos internalPos = tag.contains("internalInterfacePos")
                ? BlockPos.of(tag.getLong("internalInterfacePos"))
                : BlockPos.of(tag.getLong("internalPos"));
        ShellEndpointType type = ShellEndpointType.byName(tag.getString("type"));
        String id = tag.contains("id")
                ? tag.getString("id")
                : type.name().toLowerCase() + "@" + internalPos.toShortString();
        return new ShellBinding(
                tag.getUUID("shellId"),
                tag.getUUID("subLevelId"),
                id,
                type,
                shellFace,
                internalPos
        );
    }
}
