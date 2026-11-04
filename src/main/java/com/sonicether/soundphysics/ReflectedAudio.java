
package com.sonicether.soundphysics;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculates the apparent sound direction using reflected sound paths.
 * <p>
 * This implementation follows the original Sound Physics Remastered
 * weighted directional averaging algorithm.
 */
public final class ReflectedAudio {

    private static final double MIN_DIRECTION_LENGTH = 0.5D;

    private final double occlusion;

    private final List<DirectionSample> airspaceDirections;

    private Vec3d directDirection;

    public ReflectedAudio(double occlusion) {
        this.occlusion = occlusion;

        this.airspaceDirections = new ArrayList<>();
    }

    public boolean shouldEvaluateDirection() {
        return Config.soundDirectionEvaluation &&
                (occlusion > 0.0D ||
                        !Config.redirectNonOccludedSounds);
    }

    /**
     * Registers the original unobstructed sound path.
     * <p>
     * The direct path contributes its normalized direction
     * without an inverse-distance weight.
     */
    public void addDirectAirspace(
            Vec3d sharedAirspaceVector
    ) {
        if (!shouldEvaluateDirection() ||
                sharedAirspaceVector == null) {
            return;
        }

        if (sharedAirspaceVector.lengthSquared() < 1.0E-8D) {
            return;
        }

        directDirection = sharedAirspaceVector;
    }

    /**
     * Registers a reflected path that shares unobstructed airspace
     * with the listener.
     * <p>
     * totalRayDistance:
     * Distance travelled before reaching the reflection point.
     * <p>
     * sharedAirspaceVector:
     * Vector from the final reflection point to the listener.
     */
    public void addSharedAirspace(
            Vec3d sharedAirspaceVector,
            double totalRayDistance
    ) {
        if (!shouldEvaluateDirection() ||
                sharedAirspaceVector == null) {
            return;
        }

        double vectorLength = sharedAirspaceVector.length();

        if (vectorLength < 1.0E-4D) {
            return;
        }

        // Complete acoustic path length.
        double pathLength = totalRayDistance + vectorLength;

        if (pathLength <= 0.0D ||
                Double.isNaN(pathLength) ||
                Double.isInfinite(pathLength)) {
            return;
        }

        airspaceDirections.add(
                new DirectionSample(
                        sharedAirspaceVector,
                        pathLength
                )
        );
    }

    /**
     * Evaluates the apparent sound position.
     * <p>
     * Original Sound Physics Remastered algorithm:
     * <p>
     * 1. Initialize the direction sum with the normalized direct path.
     * 2. Add every reflected direction.
     * 3. Weight each reflected direction by 1 / distance^2.
     * 4. Normalize the resulting vector.
     * 5. Preserve the original source-to-listener distance.
     * <p>
     * No angular clustering or direction filtering is performed.
     */
    public Vec3d evaluateSoundPosition(
            Vec3d soundPos,
            Vec3d listenerPos
    ) {
        if (!shouldEvaluateDirection()) {
            return null;
        }

        if (airspaceDirections.isEmpty()) {
            return null;
        }

        Vec3d directionSum;

        if (directDirection != null) {
            directionSum = directDirection.normalize();
        } else {
            directionSum = Vec3d.ZERO;
        }

        for (DirectionSample sample : airspaceDirections) {

            double pathLength = sample.distance;

            if (pathLength <= 0.0D) {
                return null;
            }

            // Inverse-square distance weighting.
            double weight = 1.0D /
                    (pathLength * pathLength);

            Vec3d normalizedDirection =
                    sample.direction.normalize();

            directionSum = directionSum.add(
                    normalizedDirection.scale(weight)
            );
        }

        Vec3d normalized = directionSum.normalize();

        // Reject an almost-zero resulting direction.
        if (normalized.length() < MIN_DIRECTION_LENGTH) {
            return null;
        }

        // Preserve the original distance to the listener.
        double originalDistance =
                soundPos.distanceTo(listenerPos);

        return listenerPos.add(
                normalized.scale(originalDistance)
        );
    }

    private static final class DirectionSample {

        private final Vec3d direction;

        private final double distance;

        private DirectionSample(
                Vec3d direction,
                double distance
        ) {
            this.direction = direction;
            this.distance = distance;
        }
    }
}
