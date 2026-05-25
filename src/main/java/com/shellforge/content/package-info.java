/**
 * Create kinetic integration for shell blocks.
 * <p>
 * {@link FaceExtraBlockPos} marks per-face stress hubs at the same world coordinates as
 * {@link com.shellforge.block.entity.ShellBlockBlockEntity}. {@link IExtraFaceKinetics}
 * resolves those positions via {@link com.shellforge.mixin.LevelMixin}.
 * {@link IConnectionForwardingBlock} lets {@link com.shellforge.mixin.RotationPropagatorMixin}
 * walk through the shell block to the correct hub.
 */
package com.shellforge.content;
