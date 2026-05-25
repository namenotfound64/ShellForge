package com.shellforge.shell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ShellDefinition(
        UUID definitionId,
        BlockPos minCorner,
        BlockPos size,
        List<ShellBlockSnapshot> blocks,
        List<ShellEndpoint> endpoints
) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("definitionId", definitionId);
        tag.putLong("minCorner", minCorner.asLong());
        tag.putLong("size", size.asLong());

        ListTag blockList = new ListTag();
        for (ShellBlockSnapshot block : blocks) {
            blockList.add(block.save());
        }
        tag.put("blocks", blockList);

        ListTag endpointList = new ListTag();
        for (ShellEndpoint endpoint : endpoints) {
            endpointList.add(endpoint.save());
        }
        tag.put("endpoints", endpointList);
        return tag;
    }

    public static ShellDefinition load(CompoundTag tag, HolderLookup.Provider registries) {
        List<ShellBlockSnapshot> blocks = new ArrayList<>();
        ListTag blockList = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            blocks.add(ShellBlockSnapshot.load(blockList.getCompound(i), registries));
        }

        List<ShellEndpoint> endpoints = new ArrayList<>();
        ListTag endpointList = tag.getList("endpoints", Tag.TAG_COMPOUND);
        for (int i = 0; i < endpointList.size(); i++) {
            endpoints.add(ShellEndpoint.load(endpointList.getCompound(i)));
        }

        return new ShellDefinition(
                tag.getUUID("definitionId"),
                BlockPos.of(tag.getLong("minCorner")),
                BlockPos.of(tag.getLong("size")),
                List.copyOf(blocks),
                List.copyOf(endpoints)
        );
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }
}
