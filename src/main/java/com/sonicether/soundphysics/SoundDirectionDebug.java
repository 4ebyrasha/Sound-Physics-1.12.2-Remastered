package com.sonicether.soundphysics;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

public final class SoundDirectionDebug {

    private static final Logger LOGGER =
            LogManager.getLogger("SoundPhysics");

    public static final int COLOR_SOURCE = 0xFFFFA000;
    public static final int COLOR_LISTENER = 0xFFFFFFFF;

    public static final int COLOR_DIRECT_CLEAR = 0xFFFFFFFF;
    public static final int COLOR_PRIMARY = 0xFFFFFF00;
    public static final int COLOR_BOUNCE = 0xFF00FFFF;

    public static final int COLOR_VALID = 0xFF00FF00;
    public static final int COLOR_BLOCKED = 0xFFFF3030;
    public static final int COLOR_UNKNOWN = 0xFFFF8000;

    public static final int COLOR_APPARENT = 0xFFFF00FF;
    public static final int COLOR_EXPECTED = 0xFF0080FF;

    private SoundDirectionDebug() {
    }

    /**
     * Kept for compatibility with the existing preInit registration.
     * No renderer or event listener is registered anymore.
     */
    public static void register() {
    }

    public static Builder begin(
            WorldClient world,
            Vec3d source,
            Vec3d listener,
            String soundName
    ) {
        return new Builder(
                Config.debugSoundDirection,
                source,
                listener,
                soundName
        );
    }

    /**
     * Visual occlusion rays are intentionally disabled.
     */
    public static void addOcclusionRay(
            WorldClient world,
            Vec3d start,
            Vec3d end,
            int color
    ) {
    }

    public static final class Builder {

        private final boolean enabled;

        private final Vec3d source;
        private final Vec3d listener;

        private final String soundName;

        private int directClearLines;
        private int primaryRayLines;
        private int bounceLines;

        private int validPaths;
        private int blockedPaths;
        private int unknownPaths;

        private Builder(
                boolean enabled,
                Vec3d source,
                Vec3d listener,
                String soundName
        ) {
            this.enabled = enabled;
            this.source = source;
            this.listener = listener;
            this.soundName = soundName;
        }

        public void addLine(
                Vec3d from,
                Vec3d to,
                int color
        ) {
            if (!enabled) {
                return;
            }

            if (color == COLOR_DIRECT_CLEAR) {
                directClearLines++;
            } else if (color == COLOR_PRIMARY) {
                primaryRayLines++;
            } else if (color == COLOR_BOUNCE) {
                bounceLines++;
            } else if (color == COLOR_VALID) {
                validPaths++;
            } else if (color == COLOR_BLOCKED) {
                blockedPaths++;
            } else if (color == COLOR_UNKNOWN) {
                unknownPaths++;
            }
        }

        public void addLine(
                Vec3d from,
                Vec3d to,
                int color,
                boolean throughWalls
        ) {
            addLine(from, to, color);
        }

        public void addMarker(
                Vec3d position,
                int color,
                double size
        ) {
            // No visual markers.
        }

        public void finish(Vec3d apparentPosition) {
            finish(
                    apparentPosition,
                    null,
                    -1,
                    Double.NaN
            );
        }

        public void finish(
                Vec3d apparentPosition,
                ReflectedAudio reflectedAudio,
                int sourceID,
                double occlusion
        ) {
            if (!enabled) {
                return;
            }

            Vec3d originalDirection =
                    source.subtract(listener).normalize();

            Vec3d apparentDirection = apparentPosition == null
                    ? Vec3d.ZERO
                    : apparentPosition.subtract(listener).normalize();

            double angleDifference = Double.NaN;

            if (apparentPosition != null) {
                double dot = originalDirection.dotProduct(
                        apparentDirection
                );

                dot = Math.max(-1.0D, Math.min(1.0D, dot));

                angleDifference = Math.toDegrees(
                        Math.acos(dot)
                );
            }

            String status = reflectedAudio == null
                    ? "NOT_AVAILABLE"
                    : reflectedAudio.getResultStatus();

            int samples = reflectedAudio == null
                    ? 0
                    : reflectedAudio.getSampleCount();

            double coherence = reflectedAudio == null
                    ? 0.0D
                    : reflectedAudio.getDirectionCoherence();

            boolean directDirection =
                    reflectedAudio != null &&
                            reflectedAudio.hasDirectDirection();

            String sampleSummary = reflectedAudio == null
                    ? "[]"
                    : reflectedAudio.getSampleSummary(8);

            LOGGER.info(
                    String.format(
                            Locale.ROOT,
                            "[Sound Direction] sound=%s sourceID=%d status=%s " +
                                    "occlusion=%.4f " +
                                    "source=%s listener=%s " +
                                    "originalDir=%s apparentDir=%s " +
                                    "angleDifference=%.2f degrees " +
                                    "apparentPosition=%s " +
                                    "directPath=%s " +
                                    "primaryRays=%d bounceSegments=%d " +
                                    "validPaths=%d blockedPaths=%d unknownPaths=%d " +
                                    "reflectionSamples=%d coherence=%.5f " +
                                    "samples=%s",
                            soundName,
                            sourceID,
                            status,
                            occlusion,
                            source,
                            listener,
                            originalDirection,
                            apparentDirection,
                            angleDifference,
                            apparentPosition,
                            directDirection ? "CLEAR" : "NO_DIRECT_PATH",
                            primaryRayLines,
                            bounceLines,
                            validPaths,
                            blockedPaths,
                            unknownPaths,
                            samples,
                            coherence,
                            sampleSummary
                    )
            );
        }
    }
}
