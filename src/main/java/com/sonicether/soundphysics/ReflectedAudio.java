package com.sonicether.soundphysics;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculates the apparent sound direction using reflected sound paths.

 * Instead of averaging every available direction, this implementation
 * selects the dominant angular cluster of reflected paths.
 */
public final class ReflectedAudio {

    private static final double DIRECTION_THRESHOLD =
            Math.cos(Math.toRadians(30.0D));

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
                (occlusion > 0.0D || !Config.redirectNonOccludedSounds);
    }

    public void addDirectAirspace(Vec3d sharedAirspaceVector) {
        if (!shouldEvaluateDirection() || sharedAirspaceVector == null) {
            return;
        }

        if (sharedAirspaceVector.lengthSquared() < 1.0E-8D) {
            return;
        }

        directDirection = sharedAirspaceVector;
    }

    public void addSharedAirspace(
            Vec3d sharedAirspaceVector,
            double totalRayDistance
    ) {
        if (!shouldEvaluateDirection() || sharedAirspaceVector == null) {
            return;
        }

        double vectorLength = sharedAirspaceVector.length();

        if (vectorLength < 1.0E-4D) {
            return;
        }

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
     * Selects the most supported direction cluster.

     * Paths pointing in approximately the same direction are grouped.
     * The group with the highest number of supporting paths wins.

     * Within that group, shorter acoustic paths receive greater weight.
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

        // When a direct path exists and non-occluded sounds
        // should not be redirected, retain the original position.
        if (directDirection != null &&
                Config.redirectNonOccludedSounds) {
            return null;
        }

        int bestClusterCount = 0;
        double bestClusterWeight = -1.0D;

        Vec3d bestDirectionSum = Vec3d.ZERO;

        for (DirectionSample candidate : airspaceDirections) {

            Vec3d candidateDirection =
                    candidate.direction.normalize();

            Vec3d directionSum = Vec3d.ZERO;

            int clusterCount = 0;
            double clusterWeight = 0.0D;

            for (DirectionSample sample : airspaceDirections) {

                Vec3d sampleDirection =
                        sample.direction.normalize();

                double dot = candidateDirection.dotProduct(
                        sampleDirection
                );

                // Ignore paths belonging to another direction.
                if (dot < DIRECTION_THRESHOLD) {
                    continue;
                }

                double weight = 1.0D /
                        (sample.distance * sample.distance);

                directionSum = directionSum.add(
                        sampleDirection.scale(weight)
                );

                clusterWeight += weight;
                clusterCount++;
            }

            // Prefer the direction supported by the most paths.
            // If support is equal, prefer the stronger weighted cluster.
            if (clusterCount > bestClusterCount ||
                    (clusterCount == bestClusterCount &&
                            clusterWeight > bestClusterWeight)) {

                bestClusterCount = clusterCount;
                bestClusterWeight = clusterWeight;
                bestDirectionSum = directionSum;
            }
        }

        if (bestClusterCount == 0) {
            return null;
        }

        Vec3d normalized = bestDirectionSum.normalize();

        if (normalized.length() < MIN_DIRECTION_LENGTH) {
            return null;
        }

        // Preserve the original distance to the listener.
        double originalDistance = soundPos.distanceTo(listenerPos);

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
