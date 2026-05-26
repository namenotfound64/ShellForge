package com.shellforge.shell;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relays Create wireless redstone ({@code create:redstone_link}) across a shell boundary.
 * <p>
 * When the sublevel contains a receiver on a frequency, external transmitters within Create link range
 * of the shell block are merged into internal receivers within range of a redstone interface. When the
 * sublevel contains a transmitter, its signal is merged into external receivers within link range of the shell block.
 */
public final class ShellWirelessRedstoneBridge {
    private final List<RedstoneLinkBlockEntity> internalLinks = new ArrayList<>();
    private final Set<Couple<Frequency>> internalReceiverFrequencies = new HashSet<>();
    private final Set<Couple<Frequency>> internalTransmitterFrequencies = new HashSet<>();

    public void refreshInternalLinks(ServerSubLevel subLevel) {
        internalLinks.clear();
        internalReceiverFrequencies.clear();
        internalTransmitterFrequencies.clear();

        LevelPlot plot = subLevel.getPlot();
        EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic chunkBounds = chunk.getBoundingBox();
            if (chunkBounds == null) {
                continue;
            }
            for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
                for (int y = chunkBounds.minY(); y <= chunkBounds.maxY(); y++) {
                    for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                        BlockPos localPos = new BlockPos(
                                x + chunk.getPos().getMinBlockX(),
                                y,
                                z + chunk.getPos().getMinBlockZ()
                        );
                        BlockEntity blockEntity = accessor.getBlockEntity(localPos);
                        if (!(blockEntity instanceof RedstoneLinkBlockEntity link)) {
                            continue;
                        }
                        LinkBehaviour behaviour = link.getBehaviour(LinkBehaviour.TYPE);
                        if (behaviour == null || isEmptyFrequency(behaviour.getNetworkKey())) {
                            continue;
                        }
                        internalLinks.add(link);
                        if (behaviour.isListening()) {
                            internalReceiverFrequencies.add(behaviour.getNetworkKey());
                        } else {
                            internalTransmitterFrequencies.add(behaviour.getNetworkKey());
                        }
                    }
                }
            }
        }
    }

    public void clear() {
        internalLinks.clear();
        internalReceiverFrequencies.clear();
        internalTransmitterFrequencies.clear();
    }

    public boolean hasLinks() {
        return !internalLinks.isEmpty();
    }

    public void bridge(
            ServerLevel mainLevel,
            BlockPos shellBlockPos,
            List<BlockPos> redstoneInterfacePositions
    ) {
        if (internalReceiverFrequencies.isEmpty() && internalTransmitterFrequencies.isEmpty()) {
            return;
        }

        for (Couple<Frequency> frequency : internalReceiverFrequencies) {
            int crossPower = collectExternalCrossPower(mainLevel, frequency, shellBlockPos);
            applyMergedPowerToInternalReceivers(frequency, crossPower, redstoneInterfacePositions);
        }

        for (Couple<Frequency> frequency : internalTransmitterFrequencies) {
            int crossPower = collectInternalTransmitPower().getOrDefault(frequency, 0);
            applyMergedPowerToExternalReceivers(mainLevel, frequency, crossPower, shellBlockPos);
        }
    }

    /**
     * Max external transmit strength on {@code frequency} from transmitters within Create link range of the shell block.
     */
    private static int collectExternalCrossPower(ServerLevel mainLevel, Couple<Frequency> frequency, BlockPos shellBlockPos) {
        Set<IRedstoneLinkable> network = Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(mainLevel).get(frequency);
        if (network == null || network.isEmpty()) {
            return 0;
        }
        BridgePos shellAnchor = new BridgePos(shellBlockPos);
        int max = 0;
        for (IRedstoneLinkable linkable : network) {
            if (linkable.isListening()) {
                continue;
            }
            if (!RedstoneLinkNetworkHandler.withinRange(shellAnchor, linkable)) {
                continue;
            }
            max = Math.max(max, linkable.getTransmittedStrength());
        }
        return max;
    }

    private Map<Couple<Frequency>, Integer> collectInternalTransmitPower() {
        Map<Couple<Frequency>, Integer> result = new HashMap<>();
        for (RedstoneLinkBlockEntity link : internalLinks) {
            LinkBehaviour behaviour = link.getBehaviour(LinkBehaviour.TYPE);
            if (behaviour == null || behaviour.isListening()) {
                continue;
            }
            result.merge(behaviour.getNetworkKey(), behaviour.getTransmittedStrength(), Math::max);
        }
        return result;
    }

    private static boolean isWithinRangeOfAnyInterface(BlockPos receiverPos, List<BlockPos> interfacePositions) {
        BridgePos receiverAnchor = new BridgePos(receiverPos);
        for (BlockPos interfacePos : interfacePositions) {
            if (RedstoneLinkNetworkHandler.withinRange(new BridgePos(interfacePos), receiverAnchor)) {
                return true;
            }
        }
        return false;
    }

    private void applyMergedPowerToInternalReceivers(
            Couple<Frequency> frequency,
            int crossPower,
            List<BlockPos> redstoneInterfacePositions
    ) {
        for (RedstoneLinkBlockEntity receiver : internalLinks) {
            LinkBehaviour receiverLink = receiver.getBehaviour(LinkBehaviour.TYPE);
            if (receiverLink == null || !receiverLink.isListening()) {
                continue;
            }
            if (!receiverLink.getNetworkKey().equals(frequency)) {
                continue;
            }
            int localPower = computeInternalLocalTransmitPower(receiverLink, frequency, internalLinks);
            int merged = localPower;
            if (crossPower > 0 && isWithinRangeOfAnyInterface(receiver.getBlockPos(), redstoneInterfacePositions)) {
                merged = Math.max(localPower, crossPower);
            }
            applyReceiverPower(receiver, merged);
        }
    }

    private static void applyMergedPowerToExternalReceivers(
            ServerLevel mainLevel,
            Couple<Frequency> frequency,
            int crossPower,
            BlockPos shellBlockPos
    ) {
        Set<IRedstoneLinkable> network = Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(mainLevel).get(frequency);
        if (network == null || network.isEmpty()) {
            return;
        }
        List<IRedstoneLinkable> transmitters = new ArrayList<>();
        for (IRedstoneLinkable linkable : network) {
            if (!linkable.isListening()) {
                transmitters.add(linkable);
            }
        }
        for (IRedstoneLinkable linkable : network) {
            if (!linkable.isListening()) {
                continue;
            }
            BlockEntity blockEntity = mainLevel.getBlockEntity(linkable.getLocation());
            if (!(blockEntity instanceof RedstoneLinkBlockEntity receiver)) {
                continue;
            }
            LinkBehaviour receiverLink = receiver.getBehaviour(LinkBehaviour.TYPE);
            if (receiverLink == null) {
                continue;
            }
            int localPower = computeExternalLocalTransmitPower(receiverLink, frequency, transmitters);
            int merged = localPower;
            if (crossPower > 0 && RedstoneLinkNetworkHandler.withinRange(new BridgePos(shellBlockPos), receiverLink)) {
                merged = Math.max(localPower, crossPower);
            }
            applyReceiverPower(receiver, merged);
        }
    }

    private static int computeExternalLocalTransmitPower(
            LinkBehaviour receiverLink,
            Couple<Frequency> frequency,
            List<IRedstoneLinkable> transmitters
    ) {
        int max = 0;
        for (IRedstoneLinkable transmitter : transmitters) {
            if (transmitter.isListening()) {
                continue;
            }
            if (!transmitter.getNetworkKey().equals(frequency)) {
                continue;
            }
            if (!RedstoneLinkNetworkHandler.withinRange(transmitter, receiverLink)) {
                continue;
            }
            max = Math.max(max, transmitter.getTransmittedStrength());
        }
        return max;
    }

    private static int computeInternalLocalTransmitPower(
            LinkBehaviour receiverLink,
            Couple<Frequency> frequency,
            List<RedstoneLinkBlockEntity> internalLinks
    ) {
        int max = 0;
        for (RedstoneLinkBlockEntity link : internalLinks) {
            LinkBehaviour transmitter = link.getBehaviour(LinkBehaviour.TYPE);
            if (transmitter == null || transmitter.isListening()) {
                continue;
            }
            if (!transmitter.getNetworkKey().equals(frequency)) {
                continue;
            }
            if (!RedstoneLinkNetworkHandler.withinRange(transmitter, receiverLink)) {
                continue;
            }
            max = Math.max(max, transmitter.getTransmittedStrength());
        }
        return max;
    }

    private static void applyReceiverPower(RedstoneLinkBlockEntity receiver, int power) {
        if (receiver.getReceivedSignal() == power) {
            return;
        }
        receiver.setSignal(power);
        if (!(receiver.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = receiver.getBlockState();
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            boolean powered = receiver.getReceivedSignal() > 0;
            if (state.getValue(BlockStateProperties.POWERED) != powered) {
                serverLevel.setBlockAndUpdate(receiver.getBlockPos(), state.setValue(BlockStateProperties.POWERED, powered));
                state = receiver.getBlockState();
            }
        }
        receiver.updateSelfAndAttached(state);
    }

    private static boolean isEmptyFrequency(Couple<Frequency> frequency) {
        return frequency == null || (frequency.getFirst() == Frequency.EMPTY && frequency.getSecond() == Frequency.EMPTY);
    }

    /** Fixed-position link anchor for Create {@link RedstoneLinkNetworkHandler#withinRange} checks. */
    private record BridgePos(BlockPos pos) implements IRedstoneLinkable {
        @Override
        public int getTransmittedStrength() {
            return 0;
        }

        @Override
        public void setReceivedStrength(int power) {
        }

        @Override
        public boolean isListening() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public Couple<Frequency> getNetworkKey() {
            return Couple.create(Frequency.EMPTY, Frequency.EMPTY);
        }

        @Override
        public BlockPos getLocation() {
            return pos;
        }
    }
}
