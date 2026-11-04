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

    private final double occlusion;

    private final List<DirectionSample> airspaceDirections;

    private Vec3d directDirection;

    private double directionCoherence;

    private Vec3d resultingDirection = Vec3d.ZERO;

    private String resultStatus = "NOT_EVALUATED";

    public ReflectedAudio(double occlusion) {
        this.occlusion = occlusion;
        this.airspaceDirections = new ArrayList<>();
    }

    public boolean shouldEvaluateDirection() {
        return Config.soundDirectionEvaluation &&
                (occlusion > 0.0D ||
                        !Config.redirectNonOccludedSounds);
    }

    public void addDirectAirspace(Vec3d sharedAirspaceVector) {
        if (!shouldEvaluateDirection() ||
                sharedAirspaceVector == null) {
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
        if (!shouldEvaluateDirection() ||
                sharedAirspaceVector == null) {
            return;
        }

        double vectorLength = sharedAirspaceVector.length();

        if (vectorLength < 1.0E-4D) {
            return;
        }

        double pathLength =
                totalRayDistance + vectorLength;

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

    public Vec3d evaluateSoundPosition(
            Vec3d soundPos,
            Vec3d listenerPos
    ) {
        directionCoherence = 0.0D;
        resultingDirection = Vec3d.ZERO;

        if (!shouldEvaluateDirection()) {
            resultStatus = Config.soundDirectionEvaluation
                    ? "SKIPPED_NOT_OCCLUDED"
                    : "DIRECTION_EVALUATION_DISABLED";

            return null;
        }

        if (airspaceDirections.isEmpty()) {
            resultStatus = "NO_VISIBLE_REFLECTION_PATHS";
            return null;
        }

        Vec3d directionSum = Vec3d.ZERO;

        double totalWeight = 0.0D;

        /*
         * Direct path has weight 1.
         * Reflections use inverse-square path-length weighting.
         */
        if (directDirection != null) {
            Vec3d normalizedDirect =
                    directDirection.normalize();

            if (normalizedDirect.lengthSquared() > 0.0D) {
                directionSum = directionSum.add(normalizedDirect);
                totalWeight += 1.0D;
            }
        }

        for (DirectionSample sample : airspaceDirections) {

            double pathLength = sample.distance;

            if (pathLength <= 0.0D ||
                    Double.isNaN(pathLength) ||
                    Double.isInfinite(pathLength)) {

                resultStatus = "INVALID_PATH_LENGTH";
                return null;
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
        }

        if (totalWeight <= 0.0D) {
            resultStatus = "ZERO_TOTAL_DIRECTION_WEIGHT";
            return null;
        }

        /*
         * Important:
         * Measure the length BEFORE normalization.
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
         * Better to preserve the real source position than
         * redirect sound towards an arbitrary direction.
         */
        if (directionCoherence < MIN_DIRECTION_COHERENCE) {
            resultStatus = "LOW_DIRECTION_COHERENCE";
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

        resultingDirection = normalized;

        resultStatus = "REDIRECTED";

        return listenerPos.add(
                normalized.scale(originalDistance)
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

            double weight =
                    1.0D / (sample.distance * sample.distance);

            if (i > 0) {
                result.append(", ");
            }

            result.append(
                    String.format(
                            Locale.ROOT,
                            "#%d dir=(%.3f, %.3f, %.3f) path=%.2f weight=%.6f",
                            i,
                            direction.x,
                            direction.y,
                            direction.z,
                            sample.distance,
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
