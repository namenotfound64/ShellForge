package com.shellforge.block.entity;

import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.ShellBinding;
import com.shellforge.shell.ShellInstance;
import com.shellforge.shell.ShellManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShellCoreBlockEntity extends BlockEntity {
    private @Nullable ShellInstance instance;

    public ShellCoreBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.SHELL_CORE.get(), pos, state);
    }

    public void setInstance(ShellInstance instance) {
        this.instance = instance;
        setChanged();
    }

    public @Nullable ShellInstance getInstance() {
        return instance;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (instance == null) {
            return;
        }

        tag.putUUID("shellId", instance.shellId());
        tag.putUUID("definitionId", instance.definitionId());
        tag.putUUID("subLevelId", instance.subLevelId());
        tag.putLong("origin", instance.origin().asLong());
        tag.putLong("structureWorldOrigin", instance.structureWorldOrigin().asLong());

        ListTag bindings = new ListTag();
        instance.bindings().forEach(binding -> bindings.add(binding.save()));
        tag.put("bindings", bindings);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (!tag.hasUUID("shellId")) {
            instance = null;
            return;
        }

        List<ShellBinding> bindings = new ArrayList<>();
        ListTag bindingTags = tag.getList("bindings", Tag.TAG_COMPOUND);
        for (int i = 0; i < bindingTags.size(); i++) {
            bindings.add(ShellBinding.load(bindingTags.getCompound(i)));
        }

        UUID subLevelId = tag.getUUID("subLevelId");
        BlockPos structureWorldOrigin = resolveStructureWorldOrigin(tag, subLevelId);
        instance = new ShellInstance(
                tag.getUUID("shellId"),
                tag.hasUUID("definitionId") ? tag.getUUID("definitionId") : new UUID(0L, 0L),
                subLevelId,
                BlockPos.of(tag.getLong("origin")),
                structureWorldOrigin,
                List.copyOf(bindings)
        );
    }

    private BlockPos resolveStructureWorldOrigin(CompoundTag tag, UUID subLevelId) {
        if (tag.contains("structureWorldOrigin")) {
            return BlockPos.of(tag.getLong("structureWorldOrigin"));
        }
        if (level instanceof ServerLevel serverLevel) {
            ServerSubLevel subLevel = ShellManager.getSubLevel(serverLevel, subLevelId);
            if (subLevel != null) {
                return subLevel.getPlot().getCenterBlock();
            }
        }
        return BlockPos.ZERO;
    }
}
