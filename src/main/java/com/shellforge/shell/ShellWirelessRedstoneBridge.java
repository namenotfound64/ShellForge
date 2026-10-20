package com.shellforge.shell;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relays Create wireless redstone ({@code create:redstone_link}) across a shell boundary.
 * <p>
 * External transmitters within Create link range of the shell block are merged into every internal
 * receiver on the same frequency, regardless of position inside the sublevel. Internal transmitters
 * are merged into external receivers within link range of the shell block.
 */
public final class ShellWirelessRedstoneBridge {
    private final ShellWirelessRedstoneDiagnostics diagnostics = new ShellWirelessRedstoneDiagnostics();
    private final List<RedstoneLinkBlockEntity> internalLinks = new ArrayList<>();
    private final Set<Couple<Frequency>> internalReceiverFrequencies = new HashSet<>();
    private final Set<Couple<Frequency>> internalTransmitterFrequencies = new HashSet<>();

    public void refreshInternalLinks(ServerSubLevel subLevel, BlockPos shellAnchorForLog, BlockPos structureSize) {
        internalLinks.clear();
        internalReceiverFrequencies.clear();
        internalTransmitterFrequencies.clear();

        if (structureSize.getX() <= 0 || structureSize.getY() <= 0 || structureSize.getZ() <= 0) {
            diagnostics.logRefresh(
                    subLevel.getLevel(),
                    shellAnchorForLog,
                    null,
                    0, 0, 0, 0,
                    0, 0
            );
            return;
        }

        LevelPlot plot = subLevel.getPlot();
        EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();

        int linkEntities = 0;
        int behaviourMissing = 0;
        int emptyFrequency = 0;
        BlockPos scanMax = structureSize.offset(-1, -1, -1);

        // Blocks are materialized at snapshot.relativePos() (0 .. size-1), not at plot center/chunk bounds.
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.ZERO, scanMax)) {
            BlockEntity blockEntity = accessor.getBlockEntity(pos);
            if (blockEntity instanceof RedstoneLinkBlockEntity) {
                linkEntities++;
            }
            RegisterResult result = registerInternalLink(blockEntity);
            if (result == RegisterResult.BEHAVIOUR_MISSING) {
                behaviourMissing++;
            } else if (result == RegisterResult.EMPTY_FREQUENCY) {
                emptyFrequency++;
            }
        }

        diagnostics.logRefresh(
                subLevel.getLevel(),
                shellAnchorForLog,
                new ShellWirelessRedstoneDiagnostics.BoundingSummary(
                        0, 0, 0,
                        scanMax.getX(), scanMax.getY(), scanMax.getZ()
                ),
                linkEntities,
                internalLinks.size(),
                behaviourMissing,
                emptyFrequency,
                internalReceiverFrequencies.size(),
                internalTransmitterFrequencies.size()
        );
    }

    private enum RegisterResult {
        NOT_LINK,
        REGISTERED,
        BEHAVIOUR_MISSING,
        EMPTY_FREQUENCY
    }

    private RegisterResult registerInternalLink(@Nullable BlockEntity blockEntity) {
        if (!(blockEntity instanceof RedstoneLinkBlockEntity link)) {
            return RegisterResult.NOT_LINK;
        }
        boolean initialized = ensureLinkReady(link);
        if (initialized) {
            diagnostics.logEnsureLinkReady(link.getBlockPos(), true);
        }
        LinkBehaviour behaviour = link.getBehaviour(LinkBehaviour.TYPE);
        if (behaviour == null) {
            diagnostics.logRegisterSkip(link.getBlockPos(), "LinkBehaviour null after ensureLinkReady");
            return RegisterResult.BEHAVIOUR_MISSING;
        }
        if (isEmptyFrequency(behaviour.getNetworkKey())) {
            diagnostics.logRegisterSkip(link.getBlockPos(), "empty frequency");
            return RegisterResult.EMPTY_FREQUENCY;
        }
        internalLinks.add(link);
        if (behaviour.isListening()) {
            internalReceiverFrequencies.add(behaviour.getNetworkKey());
        } else {
            internalTransmitterFrequencies.add(behaviour.getNetworkKey());
        }
        return RegisterResult.REGISTERED;
    }

    /**
     * Embedded plot block entities often never tick at remote Y, so Create deferred behaviours
     * (including {@link LinkBehaviour}) are never attached unless we initialize them here.
     */
    private static boolean ensureLinkReady(RedstoneLinkBlockEntity link) {
        if (link.getLevel() == null || link.getLevel().isClientSide()) {
            return false;
        }
        if (link.getBehaviour(LinkBehaviour.TYPE) != null) {
            return false;
        }
        List<BlockEntityBehaviour> deferred = new ArrayList<>();
        link.addBehavioursDeferred(deferred);
        for (BlockEntityBehaviour behaviour : deferred) {
            link.attachBehaviourLate(behaviour);
        }
        link.initialize();
        return true;
    }

    public void clear() {
        internalLinks.clear();
        internalReceiverFrequencies.clear();
        internalTransmitterFrequencies.clear();
    }

    public boolean hasLinks() {
        return !internalLinks.isEmpty();
    }

    public void logEntry(
            String source,
            ServerLevel mainLevel,
            BlockPos shellAnchor,
            BlockPos shellWorldPos,
            boolean subLevelResolved,
            boolean hasLinks
    ) {
        diagnostics.logEntry(source, mainLevel, shellAnchor, shellWorldPos, subLevelResolved, hasLinks);
    }

    public void bridge(ServerLevel mainLevel, BlockPos shellBlockPos, UUID shellSubLevelId, String source) {
        if (internalReceiverFrequencies.isEmpty() && internalTransmitterFrequencies.isEmpty()) {
            return;
        }

        refreshInternalTransmitterSignals();

        Couple<Frequency> firstRxFreq = internalReceiverFrequencies.stream().findFirst().orElse(null);
        Couple<Frequency> firstTxFreq = internalTransmitterFrequencies.stream().findFirst().orElse(null);
        int crossIn = 0;
        int internalRxApplied = 0;

        for (Couple<Frequency> frequency : internalReceiverFrequencies) {
            crossIn = collectExternalCrossPower(mainLevel, frequency, shellBlockPos, shellSubLevelId);
            internalRxApplied += applyMergedPowerToInternalReceivers(mainLevel, frequency, crossIn);
        }

        int crossOut = 0;
        int externalRxApplied = 0;
        Map<Couple<Frequency>, Integer> internalTxPower = collectInternalTransmitPower();

        for (Couple<Frequency> frequency : internalTransmitterFrequencies) {
            crossOut = internalTxPower.getOrDefault(frequency, 0);
            externalRxApplied += applyMergedPowerToExternalReceivers(
                    mainLevel, frequency, crossOut, shellBlockPos, shellSubLevelId);
        }

        diagnostics.logBridge(
                mainLevel,
                source,
                shellBlockPos,
                firstRxFreq,
                crossIn,
                firstTxFreq,
                crossOut,
                internalRxApplied,
                externalRxApplied
        );
    }

    /**
     * Max external transmit strength on {@code frequency} from transmitters within Create link range of the shell block.
     * Transmitters on this shell's Sable physical structure use plot-global coords and are always merged; others are
     * range-checked after {@link Sable#HELPER projecting} out of the sublevel.
     */
    private int collectExternalCrossPower(
            ServerLevel mainLevel,
            Couple<Frequency> frequency,
            BlockPos shellBlockPos,
            @Nullable UUID shellSubLevelId
    ) {
        Set<IRedstoneLinkable> network = Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(mainLevel).get(frequency);
        if (network == null || network.isEmpty()) {
            diagnostics.logExternalCollect(mainLevel, shellBlockPos, frequency, 0, 0, 0);
            return 0;
        }
        BridgePos shellAnchor = new BridgePos(shellBlockPos);
        int max = 0;
        int inRange = 0;
        for (IRedstoneLinkable linkable : network) {
            if (linkable.isListening()) {
                continue;
            }
            if (!isTransmitterInShellRange(mainLevel, shellAnchor, shellSubLevelId, linkable)) {
                continue;
            }
            inRange++;
            max = Math.max(max, linkable.getTransmittedStrength());
        }
        diagnostics.logExternalCollect(mainLevel, shellBlockPos, frequency, network.size(), inRange, max);
        return max;
    }

    private void refreshInternalTransmitterSignals() {
        for (RedstoneLinkBlockEntity link : internalLinks) {
            LinkBehaviour behaviour = link.getBehaviour(LinkBehaviour.TYPE);
            if (behaviour == null || behaviour.isListening()) {
                continue;
            }
            BlockState state = link.getBlockState();
            if (state.getBlock() instanceof RedstoneLinkBlock block) {
                block.updateTransmittedSignal(state, link.getLevel(), link.getBlockPos());
            }
        }
    }

    private Map<Couple<Frequency>, Integer> collectInternalTransmitPower() {
        Map<Couple<Frequency>, Integer> result = new HashMap<>();
        for (RedstoneLinkBlockEntity link : internalLinks) {
            LinkBehaviour behaviour = link.getBehaviour(LinkBehaviour.TYPE);
            if (behaviour == null || behaviour.isListening()) {
                continue;
            }
            int strength = Math.max(behaviour.getTransmittedStrength(), link.getSignal());
            result.merge(behaviour.getNetworkKey(), strength, Math::max);
        }
        return result;
    }

    private int applyMergedPowerToInternalReceivers(
            ServerLevel mainLevel,
            Couple<Frequency> frequency,
            int crossPower
    ) {
        int applied = 0;
        for (RedstoneLinkBlockEntity receiver : internalLinks) {
            LinkBehaviour receiverLink = receiver.getBehaviour(LinkBehaviour.TYPE);
            if (receiverLink == null || !receiverLink.isListening()) {
                continue;
            }
            if (!receiverLink.getNetworkKey().equals(frequency)) {
                continue;
            }
            int localPower = computeInternalLocalTransmitPower(mainLevel, receiverLink, frequency, internalLinks);
            if (applyReceiverPower(receiver, Math.max(localPower, crossPower), "internal-rx")) {
                applied++;
            }
        }
        return applied;
    }

    private int applyMergedPowerToExternalReceivers(
            ServerLevel mainLevel,
            Couple<Frequency> frequency,
            int crossPower,
            BlockPos shellBlockPos,
            @Nullable UUID shellSubLevelId
    ) {
        Set<IRedstoneLinkable> network = Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(mainLevel).get(frequency);
        if (network == null || network.isEmpty()) {
            return 0;
        }
        List<IRedstoneLinkable> transmitters = new ArrayList<>();
        for (IRedstoneLinkable linkable : network) {
            if (!linkable.isListening()) {
                transmitters.add(linkable);
            }
        }
        int applied = 0;
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
            int localPower = computeExternalLocalTransmitPower(mainLevel, receiverLink, frequency, transmitters);
            int merged = localPower;
            if (isReceiverInShellRange(mainLevel, new BridgePos(shellBlockPos), shellSubLevelId, receiverLink)) {
                merged = Math.max(localPower, crossPower);
            }
            if (applyReceiverPower(receiver, merged, "external-rx")) {
                applied++;
            }
        }
        return applied;
    }

    private static int computeExternalLocalTransmitPower(
            ServerLevel mainLevel,
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
            if (!withinLinkRange(mainLevel, transmitter, receiverLink)) {
                continue;
            }
            max = Math.max(max, transmitter.getTransmittedStrength());
        }
        return max;
    }

    private static int computeInternalLocalTransmitPower(
            ServerLevel mainLevel,
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
            if (!withinLinkRange(mainLevel, transmitter, receiverLink)) {
                continue;
            }
            max = Math.max(max, Math.max(transmitter.getTransmittedStrength(), link.getSignal()));
        }
        return max;
    }

    private static boolean isTransmitterInShellRange(
            ServerLevel mainLevel,
            BridgePos shellAnchor,
            @Nullable UUID shellSubLevelId,
            IRedstoneLinkable transmitter
    ) {
        if (shellSubLevelId != null && belongsToSubLevel(mainLevel, transmitter.getLocation(), shellSubLevelId)) {
            return true;
        }
        return withinRangeAtWorldPosition(mainLevel, shellAnchor, transmitter);
    }

    private static boolean isReceiverInShellRange(
            ServerLevel mainLevel,
            BridgePos shellAnchor,
            @Nullable UUID shellSubLevelId,
            LinkBehaviour receiver
    ) {
        if (shellSubLevelId != null && belongsToSubLevel(mainLevel, receiver.getLocation(), shellSubLevelId)) {
            return true;
        }
        return withinRangeAtWorldPosition(mainLevel, shellAnchor, receiver);
    }

    private static boolean withinRangeAtWorldPosition(
            ServerLevel mainLevel,
            BridgePos anchor,
            IRedstoneLinkable linkable
    ) {
        return RedstoneLinkNetworkHandler.withinRange(
                anchor,
                new BridgePos(projectLinkToWorld(mainLevel, linkable.getLocation()))
        );
    }

    private static boolean withinLinkRange(
            ServerLevel mainLevel,
            IRedstoneLinkable transmitter,
            IRedstoneLinkable receiver
    ) {
        return RedstoneLinkNetworkHandler.withinRange(
                new BridgePos(projectLinkToWorld(mainLevel, transmitter.getLocation())),
                new BridgePos(projectLinkToWorld(mainLevel, receiver.getLocation()))
        );
    }

    private static BlockPos projectLinkToWorld(ServerLevel mainLevel, BlockPos linkPos) {
        return BlockPos.containing(Sable.HELPER.projectOutOfSubLevel(mainLevel, Vec3.atCenterOf(linkPos)));
    }

    private static boolean belongsToSubLevel(ServerLevel mainLevel, BlockPos pos, UUID subLevelId) {
        SubLevel subLevel = Sable.HELPER.getContaining(mainLevel, pos);
        return subLevel != null && subLevel.getUniqueId().equals(subLevelId);
    }

    private boolean applyReceiverPower(RedstoneLinkBlockEntity receiver, int power, String side) {
        int oldPower = receiver.getReceivedSignal();
        if (oldPower == power) {
            return false;
        }
        receiver.setSignal(power);
        Level level = receiver.getLevel();
        if (level == null || level.isClientSide()) {
            diagnostics.logApplyReceiver(receiver.getBlockPos(), side, oldPower, power, level == null ? "null" : "client");
            return true;
        }
        BlockState state = receiver.getBlockState();
        if (state.getBlock() instanceof RedstoneLinkBlock && state.hasProperty(RedstoneLinkBlock.POWERED)) {
            boolean powered = receiver.getReceivedSignal() > 0;
            if (state.getValue(RedstoneLinkBlock.POWERED) != powered) {
                level.setBlockAndUpdate(receiver.getBlockPos(), state.setValue(RedstoneLinkBlock.POWERED, powered));
                state = receiver.getBlockState();
            }
        }
        receiver.updateSelfAndAttached(state);
        diagnostics.logApplyReceiver(
                receiver.getBlockPos(),
                side,
                oldPower,
                power,
                level.getClass().getSimpleName()
        );
        return true;
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
