package com.shellforge.shell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

public record ShellEndpoint(
        String id,
        ShellEndpointType type,
        Direction shellFace,
        BlockPos internalInterfacePos
) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("type", type.name());
        tag.putString("shellFace", shellFace.getName());
        tag.putLong("internalInterfacePos", internalInterfacePos.asLong());
        return tag;
    }

    public static ShellEndpoint load(CompoundTag tag) {
        Direction shellFace = tag.contains("shellFace")
                ? Direction.byName(tag.getString("shellFace"))
                : Direction.byName(tag.getString("face"));
        BlockPos internalPos = tag.contains("internalInterfacePos")
                ? BlockPos.of(tag.getLong("internalInterfacePos"))
                : BlockPos.of(tag.getLong("internalRelativePos"));
        return new ShellEndpoint(
                tag.getString("id"),
                ShellEndpointType.byName(tag.getString("type")),
                shellFace,
                internalPos
        );
    }
}
