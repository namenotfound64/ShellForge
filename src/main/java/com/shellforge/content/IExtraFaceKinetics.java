package com.shellforge.content;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Parent block entity that hosts per-face kinetic hubs at {@link FaceExtraBlockPos}.
 */
public interface IExtraFaceKinetics {

    @Nullable
    KineticBlockEntity getFaceKinetics(Direction face);
}
