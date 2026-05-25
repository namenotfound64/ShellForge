/**
 * Mixins that hook Create's rotation and stress systems for per-face shell hubs.
 * <p>
 * Requires {@code shellforge.mixins.json}. When Simulated is present at runtime,
 * {@link KineticNetworkMixin} redirects may be skipped due to equal priority with
 * Simulated's extra_kinetics mixins; {@link RotationPropagatorMixin} and {@link LevelMixin}
 * remain essential.
 */
package com.shellforge.mixin;
