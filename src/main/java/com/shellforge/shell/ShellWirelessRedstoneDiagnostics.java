package com.shellforge.shell;

import com.shellforge.ShellForge;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Throttled logging for {@link ShellWirelessRedstoneBridge} troubleshooting.
 */
final class ShellWirelessRedstoneDiagnostics {
    private static final int SUMMARY_INTERVAL_TICKS = 40;

    private long lastSummaryGameTime = -1;
    private int lastInternalLinkCount = -1;
    private int lastCrossIn = Integer.MIN_VALUE;
    private int lastCrossOut = Integer.MIN_VALUE;
    private boolean lastHadLinks = true;

    void logEntry(
            String source,
            ServerLevel mainLevel,
            BlockPos shellBlockPos,
            BlockPos shellWorldPos,
            boolean subLevelResolved,
            boolean hasLinks
    ) {
        if (hasLinks != lastHadLinks) {
            lastHadLinks = hasLinks;
            ShellForge.LOGGER.info(
                    "[WirelessRedstone] {} shellAnchor={} worldPos={} subLevelResolved={} hasLinks={}",
                    source,
                    shellBlockPos.toShortString(),
                    shellWorldPos.toShortString(),
                    subLevelResolved,
                    hasLinks
            );
        }
        if (!hasLinks) {
            if (shouldLogSummary(mainLevel)) {
                ShellForge.LOGGER.warn(
                        "[WirelessRedstone] {} no internal links at shell={} (worldPos={}, subLevelResolved={})",
                        source,
                        shellBlockPos.toShortString(),
                        shellWorldPos.toShortString(),
                        subLevelResolved
                );
            }
        }
    }

    void logRefresh(
            ServerLevel mainLevel,
            BlockPos shellBlockPos,
            @Nullable BoundingSummary bounds,
            int redstoneLinkEntities,
            int registeredLinks,
            int behaviourMissing,
            int emptyFrequency,
            int receivers,
            int transmitters
    ) {
        boolean changed = registeredLinks != lastInternalLinkCount;
        lastInternalLinkCount = registeredLinks;

        if (!changed && !shouldLogSummary(mainLevel)) {
            return;
        }

        if (bounds == null) {
            ShellForge.LOGGER.warn(
                    "[WirelessRedstone] refresh shell={}: plot bounds empty",
                    shellBlockPos.toShortString()
            );
            return;
        }

        ShellForge.LOGGER.info(
                "[WirelessRedstone] refresh shell={} relativeBounds=[{},{},{} -> {},{},{}] "
                        + "linkBEs={} registered={} skip(noBehaviour={} emptyFreq={}) rxFreq={} txFreq={}",
                shellBlockPos.toShortString(),
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                redstoneLinkEntities,
                registeredLinks,
                behaviourMissing,
                emptyFrequency,
                receivers,
                transmitters
        );
    }

    void logBridge(
            ServerLevel mainLevel,
            String source,
            BlockPos shellAnchor,
            Couple<Frequency> rxFrequency,
            int crossIn,
            Couple<Frequency> txFrequency,
            int crossOut,
            int internalRxApplied,
            int externalRxApplied
    ) {
        boolean crossChanged = crossIn != lastCrossIn || crossOut != lastCrossOut;
        lastCrossIn = crossIn;
        lastCrossOut = crossOut;

        if (!crossChanged && !shouldLogSummary(mainLevel)) {
            return;
        }

        ShellForge.LOGGER.info(
                "[WirelessRedstone] bridge {} shell={} "
                        + "crossIn(freq={}, power={}) -> internalRx={} | "
                        + "crossOut(freq={}, power={}) -> externalRx={}",
                source,
                shellAnchor.toShortString(),
                formatFrequency(rxFrequency),
                crossIn,
                internalRxApplied,
                formatFrequency(txFrequency),
                crossOut,
                externalRxApplied
        );
    }

    void logExternalCollect(
            ServerLevel mainLevel,
            BlockPos shellAnchor,
            Couple<Frequency> frequency,
            int networkSize,
            int transmittersInRange,
            int maxPower
    ) {
        if (maxPower == 0 && shouldLogSummary(mainLevel)) {
            ShellForge.LOGGER.info(
                    "[WirelessRedstone] collectExternal shell={} freq={} network={} inRangeTx={} maxPower=0",
                    shellAnchor.toShortString(),
                    formatFrequency(frequency),
                    networkSize,
                    transmittersInRange
            );
        } else if (maxPower > 0 && maxPower != lastCrossIn) {
            ShellForge.LOGGER.info(
                    "[WirelessRedstone] collectExternal shell={} freq={} network={} inRangeTx={} maxPower={}",
                    shellAnchor.toShortString(),
                    formatFrequency(frequency),
                    networkSize,
                    transmittersInRange,
                    maxPower
            );
        }
    }

    void logApplyReceiver(
            BlockPos pos,
            String side,
            int oldPower,
            int newPower,
            @Nullable String levelType
    ) {
        ShellForge.LOGGER.info(
                "[WirelessRedstone] apply {} pos={} {} -> {} level={}",
                side,
                pos.toShortString(),
                oldPower,
                newPower,
                levelType
        );
    }

    void logEnsureLinkReady(BlockPos pos, boolean initialized) {
        ShellForge.LOGGER.info(
                "[WirelessRedstone] ensureLinkReady pos={} initialized={}",
                pos.toShortString(),
                initialized
        );
    }

    void logRegisterSkip(BlockPos pos, String reason) {
        ShellForge.LOGGER.warn(
                "[WirelessRedstone] skip link pos={} reason={}",
                pos.toShortString(),
                reason
        );
    }

    private boolean shouldLogSummary(ServerLevel mainLevel) {
        long gameTime = mainLevel.getGameTime();
        if (gameTime - lastSummaryGameTime >= SUMMARY_INTERVAL_TICKS) {
            lastSummaryGameTime = gameTime;
            return true;
        }
        return false;
    }

    private static String formatFrequency(@Nullable Couple<Frequency> frequency) {
        if (frequency == null) {
            return "null";
        }
        return frequency.getFirst() + "+" + frequency.getSecond();
    }

    record BoundingSummary(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }
}
