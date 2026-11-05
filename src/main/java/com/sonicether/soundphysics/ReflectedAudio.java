
package com.sonicether.soundphysics;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReflectedAudio {

    /*
     * Minimum agreement between all contributing directions.
     *
     * 1.0 = all directions point the same way.
     * 0.0 = directions cancel each other out.
     */
    private static final double MIN_DIRECTION_COHERENCE = 0.12D;

    /*
     * Amount of the original source direction mixed
     * into the calculated reflected direction.
     *
     * 0.15 = 15% original direction, 85% calculated direction.
     */
    private static final double ORIGINAL_DIRECTION_BIAS = 0.32D;

    private final double occlusion;

    private final boolean directionExcluded;

    private final List<DirectionSample> airspaceDirections;

    private Vec3d directDirection;

    private double directionCoherence;

    private Vec3d resultingDirection = Vec3d.ZERO;

    private String resultStatus = "NOT_EVALUATED";

    public ReflectedAudio(double occlusion) {
        this(occlusion, false);
    }

    public ReflectedAudio(
            double occlusion,
            boolean directionExcluded
    ) {
        this.occlusion = occlusion;
        this.directionExcluded = directionExcluded;
        this.airspaceDirections = new ArrayList<>();
    }

    public boolean shouldEvaluateDirection() {

        return !directionExcluded &&
                Config.soundDirectionEvaluation &&
                (occlusion > 0.0D ||
                        !Config.redirectNonOccludedSounds);
    }

    public void addDirectAirspace(Vec3d sharedAirspaceVector) {

        if (!shouldEvaluateDirection() ||
                sharedAirspaceVector == null) {
            return;
        }

        double lengthSquared = sharedAirspaceVector.lengthSquared();

        if (lengthSquared < 1.0E-8D ||
                Double.isNaN(lengthSquared) ||
                Double.isInfinite(lengthSquared)) {
            return;
        }

        directDirection = sharedAirspaceVector;
    }

    public void addSharedAirspace(
            Vec3d sharedAirspaceVector,
            double totalRayDistance
    ) {

        if (!shouldEvaluateDirection() ||
                sharedAirspaceVector == null) {
            return;
        }

        double vectorLength = sharedAirspaceVector.length();

        if (vectorLength < 1.0E-4D ||
                Double.isNaN(vectorLength) ||
                Double.isInfinite(vectorLength)) {
            return;
        }

        /*
         * Total acoustic path length:
         *
         * source -> reflection point
         * +
         * reflection point -> listener
         */
        double pathLength =
                totalRayDistance + vectorLength;

        if (pathLength <= 0.0D ||
                Double.isNaN(pathLength) ||
                Double.isInfinite(pathLength)) {
            return;
        }

        /*
         * The 1.12.2 port does not have the modern
         * maxSoundProcessingDistance config option.
         *
         * Config.maxDistance is the existing maximum
         * ray tracing distance in this version.
         *
         * Config.rolloffFactor corresponds to the
         * attenuation factor used by the upstream logic.
         */
        double attenuationFactor = Config.rolloffFactor;

        if (attenuationFactor <= 0.0D ||
                Double.isNaN(attenuationFactor) ||
                Double.isInfinite(attenuationFactor)) {
            return;
        }

        double maxPathLength =
                Config.maxDistance / attenuationFactor;

        if (pathLength > maxPathLength) {
            return;
        }

        airspaceDirections.add(
                new DirectionSample(
                        sharedAirspaceVector,
                        pathLength
                )
        );
    }

    public Vec3d evaluateSoundPosition(
            Vec3d soundPos,
            Vec3d listenerPos
    ) {

        directionCoherence = 0.0D;
        resultingDirection = Vec3d.ZERO;

        if (!shouldEvaluateDirection()) {

            if (directionExcluded) {
                resultStatus = "SKIPPED_CONFIG_EXCLUSION";
            } else {
                resultStatus = Config.soundDirectionEvaluation
                        ? "SKIPPED_NOT_OCCLUDED"
                        : "DIRECTION_EVALUATION_DISABLED";
            }

            return null;
        }

        if (airspaceDirections.isEmpty()) {
            resultStatus = "NO_VISIBLE_REFLECTION_PATHS";
            return null;
        }

        Vec3d directionSum = Vec3d.ZERO;

        double totalWeight = 0.0D;

        /*
         * Weighted sum of apparent distances.
         *
         * This is deliberately separate from pathLength:
         *
         * pathLength:
         *   Used to determine how much a path contributes.
         *
         * apparentDistance:
         *   Distance from listener to the point through
         *   which this path enters the shared airspace.
         */
        double apparentDistanceSum = 0.0D;

        /*
         * Direct path has weight 1.
         * Reflections use inverse-square path-length weighting.
         */
        if (directDirection != null) {

            double directDistance = directDirection.length();

            Vec3d normalizedDirect =
                    directDirection.normalize();

            if (directDistance > 1.0E-4D &&
                    !Double.isNaN(directDistance) &&
                    !Double.isInfinite(directDistance) &&
                    normalizedDirect.lengthSquared() > 0.0D) {

                directionSum = directionSum.add(normalizedDirect);

                totalWeight += 1.0D;

                apparentDistanceSum += directDistance;
            }
        }

        for (DirectionSample sample : airspaceDirections) {

            double pathLength = sample.pathLength;

            if (pathLength <= 0.0D ||
                    Double.isNaN(pathLength) ||
                    Double.isInfinite(pathLength)) {

                resultStatus = "INVALID_PATH_LENGTH";

                return null;
            }

            /*
             * The original vector points from the listener
             * to the shared-airspace/reflection point.
             *
             * Its length is the apparent source distance.
             */
            double apparentDistance =
                    sample.direction.length();

            if (apparentDistance < 1.0E-4D ||
                    Double.isNaN(apparentDistance) ||
                    Double.isInfinite(apparentDistance)) {
                continue;
            }

            Vec3d normalizedDirection =
                    sample.direction.normalize();

            if (normalizedDirection.lengthSquared() < 1.0E-8D) {
                continue;
            }

            double weight =
                    1.0D / (pathLength * pathLength);

            if (Double.isNaN(weight) ||
                    Double.isInfinite(weight) ||
                    weight <= 0.0D) {
                continue;
            }

            directionSum = directionSum.add(
                    normalizedDirection.scale(weight)
            );

            totalWeight += weight;

            /*
             * Accumulate apparent distance using exactly
             * the same weight as the corresponding direction.
             */
            apparentDistanceSum +=
                    apparentDistance * weight;
        }

        if (totalWeight <= 0.0D) {
            resultStatus = "ZERO_TOTAL_DIRECTION_WEIGHT";
            return null;
        }

        /*
         * Measure length BEFORE normalization.
         *
         * Normalizing first would turn even a nearly cancelled
         * vector into a full-length unit direction.
         */
        double resultantLength = directionSum.length();

        if (Double.isNaN(resultantLength) ||
                Double.isInfinite(resultantLength) ||
                resultantLength < 1.0E-8D) {

            resultStatus = "DIRECTIONS_CANCELLED";

            return null;
        }

        directionCoherence = Math.min(
                1.0D,
                resultantLength / totalWeight
        );

        /*
         * Reject unstable results.
         */
        if (directionCoherence < MIN_DIRECTION_COHERENCE) {

            resultStatus = "LOW_DIRECTION_COHERENCE";

            return null;
        }

        /*
         * Calculate the weighted apparent distance.
         *
         * Do not use the original source distance here.
         * For redirected sound, this distance should point
         * towards the shared-airspace entrance, not towards
         * the actual sound source behind the obstruction.
         */
        double apparentDistance =
                apparentDistanceSum / totalWeight;

        if (apparentDistance < 1.0E-4D ||
                Double.isNaN(apparentDistance) ||
                Double.isInfinite(apparentDistance)) {

            resultStatus = "INVALID_APPARENT_DISTANCE";

            return null;
        }

        Vec3d normalized =
                directionSum.scale(1.0D / resultantLength);

        double originalDistance =
                soundPos.distanceTo(listenerPos);

        if (originalDistance < 1.0E-4D) {

            resultStatus = "SOURCE_TOO_CLOSE_TO_LISTENER";

            return null;
        }

        /*
         * Original direction from the listener to the sound source.
         */
        Vec3d originalDirection =
                soundPos.subtract(listenerPos)
                        .scale(1.0D / originalDistance);

        /*
         * Blend the calculated reflected direction with the
         * original source direction.
         *
         * This reduces excessive directional deviation.
         */
        Vec3d finalDirection =
                normalized.scale(1.0D - ORIGINAL_DIRECTION_BIAS)
                        .add(
                                originalDirection.scale(
                                        ORIGINAL_DIRECTION_BIAS
                                )
                        )
                        .normalize();

        if (finalDirection.lengthSquared() < 1.0E-8D) {

            resultStatus = "INVALID_FINAL_DIRECTION";

            return null;
        }

        resultingDirection = finalDirection;

        resultStatus = "REDIRECTED";

        /*
         * Place the virtual source at the calculated apparent
         * distance instead of the original source distance.
         */
        return listenerPos.add(
                finalDirection.scale(apparentDistance)
        );
    }

    public int getSampleCount() {
        return airspaceDirections.size();
    }

    public boolean hasDirectDirection() {
        return directDirection != null;
    }

    public double getDirectionCoherence() {
        return directionCoherence;
    }

    public Vec3d getResultingDirection() {
        return resultingDirection;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    /**
     * Returns a compact description of the contributing paths.
     */
    public String getSampleSummary(int maximumSamples) {

        if (airspaceDirections.isEmpty()) {
            return "[]";
        }

        StringBuilder result = new StringBuilder("[");

        int count = Math.min(
                maximumSamples,
                airspaceDirections.size()
        );

        for (int i = 0; i < count; i++) {

            DirectionSample sample =
                    airspaceDirections.get(i);

            Vec3d direction =
                    sample.direction.normalize();

            double apparentDistance =
                    sample.direction.length();

            double weight =
                    1.0D / (sample.pathLength * sample.pathLength);

            if (i > 0) {
                result.append(", ");
            }

            result.append(
                    String.format(
                            Locale.ROOT,
                            "#%d dir=(%.3f, %.3f, %.3f) path=%.2f apparent=%.2f weight=%.6f",
                            i,
                            direction.x,
                            direction.y,
                            direction.z,
                            sample.pathLength,
                            apparentDistance,
                            weight
                    )
            );
        }

        if (airspaceDirections.size() > count) {

            result.append(", ... +");

            result.append(airspaceDirections.size() - count);

            result.append(" more");
        }

        result.append("]");

        return result.toString();
    }

    private static final class DirectionSample {

        private final Vec3d direction;

        private final double pathLength;

        private DirectionSample(
                Vec3d direction,
                double pathLength
        ) {
            this.direction = direction;
            this.pathLength = pathLength;
        }
    }
}
