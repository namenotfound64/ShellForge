package com.shellforge.shell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record ShellBlockSnapshot(BlockPos relativePos, BlockState state, CompoundTag blockEntityTag) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("relativePos", relativePos.asLong());
        tag.put("state", NbtUtils.writeBlockState(state));
        if (blockEntityTag != null) {
            tag.put("blockEntity", blockEntityTag.copy());
        }
        return tag;
    }

    public static ShellBlockSnapshot load(CompoundTag tag, HolderLookup.Provider registries) {
        HolderLookup.RegistryLookup<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        CompoundTag blockEntityTag = tag.contains("blockEntity") ? tag.getCompound("blockEntity") : null;
        return new ShellBlockSnapshot(
                BlockPos.of(tag.getLong("relativePos")),
                NbtUtils.readBlockState(blocks, tag.getCompound("state")),
                blockEntityTag
        );
    }
}
