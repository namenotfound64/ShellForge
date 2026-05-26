package com.shellforge.block.entity;

import com.shellforge.block.StressInterfaceBlock;
import com.shellforge.registry.SFBlockEntities;
import com.shellforge.shell.ShellKineticAngleSync;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sublevel stress endpoint. When the shell hub has a source, {@link #setExternalSpeed} injects RPM and
 * capacity-per-RPM from the main world. Sign flips (gearshift) reactivate the internal source without detach
 * to avoid breaking the external hub network.
 */
public class StressInterfaceBlockEntity extends GeneratingKineticBlockEntity {
    private static final float EPSILON = 1e-4f;

    /** RPM injected from the external shell hub when it is driven from outside. */
    private float externalSpeed;
    /** Stress capacity per RPM; Create scales this by {@link #getGeneratedSpeed()} for the internal network. */
    private float externalCapacity;
    /** Absolute shaft angle mirrored from the external shell side. */
    private float externalVisualAngle;

    public StressInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public StressInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(SFBlockEntities.STRESS_INTERFACE.get(), pos, state);
    }

    /**
     * @param speed    injected RPM (0 clears injection)
     * @param capacity stress capacity <b>per RPM</b> (Create multiplies by {@link #getGeneratedSpeed()})
     */
    public void setExternalSpeed(float speed, float capacity) {
        float oldSpeed = externalSpeed;
        float absOld = Math.abs(externalSpeed);
        float absNew = Math.abs(speed);
        float oldCapacity = externalCapacity;
        externalSpeed = speed;
        externalCapacity = capacity;

        if (absNew < EPSILON) {
            if (absOld > EPSILON) {
                detachKinetics();
                removeSource();
                attachKinetics();
                setChanged();
            }
        } else if (absOld < EPSILON) {
            if (hasNetwork())
                getOrCreateNetwork().remove(this);
            detachKinetics();
            removeSource();
            reActivateSource = true;
            setChanged();
        } else {
            boolean signFlip = absOld > EPSILON && absNew > EPSILON
                    && Math.signum(oldSpeed) != Math.signum(speed);
            if (signFlip) {
                // Sign reversal (gearshift): reactivate internal source only — do not detach, which
                // retriggers propagateNewSource on the external shell hub and breaks the block.
                reActivateSource = true;
                setChanged();
                if (hasNetwork()) {
                    getOrCreateNetwork().updateCapacityFor(this, externalCapacity);
                    getOrCreateNetwork().updateNetwork();
                }
            } else if (Math.abs(oldSpeed - speed) > EPSILON) {
                reActivateSource = true;
                setChanged();
                if (hasNetwork()) {
                    getOrCreateNetwork().updateCapacityFor(this, externalCapacity);
                    getOrCreateNetwork().updateNetwork();
                }
            } else if (hasNetwork() && Math.abs(oldCapacity - externalCapacity) > EPSILON) {
                getOrCreateNetwork().updateCapacityFor(this, externalCapacity);
                setChanged();
            } else {
                setChanged();
            }
        }
    }

    public float getExternalSpeed() {
        return externalSpeed;
    }

    public float getExternalCapacity() {
        return externalCapacity;
    }

    public void setExternalVisualAngle(float angle) {
        if (Math.abs(externalVisualAngle - angle) > EPSILON) {
            externalVisualAngle = angle;
            setChanged();
        }
    }

    public float getExternalVisualAngle() {
        return externalVisualAngle;
    }

    @Override
    public int getRotationAngleOffset(Direction.Axis axis) {
        if (sequenceContext != null) {
            return 0;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(StressInterfaceBlock.FACING)
                && state.getValue(StressInterfaceBlock.FACING).getAxis() == axis) {
            return ShellKineticAngleSync.rotationOffsetForVisualAngle(this, axis, externalVisualAngle);
        }
        return 0;
    }

    @Override
    protected void copySequenceContextFrom(KineticBlockEntity sourceBE) {
        SequencedGearshiftBlockEntity.SequenceContext fromSource =
                ShellKineticAngleSync.probeSequenceContext(sourceBE);
        if (fromSource != null) {
            sequenceContext = fromSource;
            return;
        }
        super.copySequenceContextFrom(sourceBE);
    }

    @Override
    protected boolean syncSequenceContext() {
        return sequenceContext != null;
    }

    @Override
    public float getGeneratedSpeed() {
        return externalSpeed;
    }

    @Override
    public float calculateAddedStressCapacity() {
        if (Math.abs(externalSpeed) < EPSILON) {
            lastCapacityProvided = 0;
            return 0;
        }
        lastCapacityProvided = externalCapacity;
        return externalCapacity;
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putFloat("ExternalSpeed", externalSpeed);
        compound.putFloat("ExternalCapacity", externalCapacity);
        compound.putFloat("ExternalVisualAngle", externalVisualAngle);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        externalSpeed = compound.getFloat("ExternalSpeed");
        externalCapacity = compound.getFloat("ExternalCapacity");
        if (compound.contains("ExternalVisualAngle")) {
            externalVisualAngle = compound.getFloat("ExternalVisualAngle");
        } else {
            externalVisualAngle = compound.getInt("ExternalAngleOffset");
        }
    }
}
