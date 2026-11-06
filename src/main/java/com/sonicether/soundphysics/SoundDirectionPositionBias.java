package com.sonicether.soundphysics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class SoundDirectionPositionBias {

    private static volatile Map<String, Double> overrides =
            Collections.emptyMap();

    private SoundDirectionPositionBias() {
    }

    /**
     * Replaces all per-sound original position bias overrides.
     * <p>
     * Format:
     * minecraft:block.grass.step=85
     * <p>
     * Values are percentages from 0 to 100.
     */
    public static void configure(String[] entries) {

        Map<String, Double> updatedEntries = new HashMap<>();

        if (entries != null) {

            for (String entry : entries) {

                if (entry == null) {
                    continue;
                }

                String value = entry.trim();

                if (value.isEmpty()) {
                    continue;
                }

                int separator = value.lastIndexOf('=');

                if (separator <= 0 || separator >= value.length() - 1) {
                    continue;
                }

                String soundEvent = normalizeSoundEvent(
                        value.substring(0, separator)
                );

                if (soundEvent.isEmpty()) {
                    continue;
                }

                try {

                    double percentage = Double.parseDouble(
                            value.substring(separator + 1).trim()
                    );

                    if (Double.isNaN(percentage) ||
                            Double.isInfinite(percentage) ||
                            percentage < 0.0D ||
                            percentage > 100.0D) {
                        continue;
                    }

                    updatedEntries.put(
                            soundEvent,
                            percentage / 100.0D
                    );

                } catch (NumberFormatException ignored) {
                    // Invalid configuration entry.
                }
            }
        }

        overrides = Collections.unmodifiableMap(updatedEntries);
    }

    /**
     * Returns the configured original-position bias.
     * <p>
     * Returns 0 when no override exists.
     */
    public static double getBias(String soundName) {

        String soundEvent = normalizeSoundEvent(soundName);

        if (soundEvent.isEmpty()) {
            return 0.0D;
        }

        Double bias = overrides.get(soundEvent);

        return bias != null ? bias : 0.0D;
    }

    /**
     * Sound names can have this format:
     * <p>
     * namespace:event_id|sound/file/path
     * <p>
     * Only the event ID is used for matching.
     */
    private static String normalizeSoundEvent(String soundName) {

        if (soundName == null) {
            return "";
        }

        String result = soundName.trim();

        int separator = result.indexOf('|');

        if (separator >= 0) {
            result = result.substring(0, separator).trim();
        }

        return result.toLowerCase(Locale.ROOT);
    }
}
