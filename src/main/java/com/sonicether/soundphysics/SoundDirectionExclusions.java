package com.sonicether.soundphysics;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SoundDirectionExclusions {

    private static volatile Set<String> excludedSoundEvents =
            Collections.emptySet();

    private SoundDirectionExclusions() {
    }

    /**
     * Replaces the current list of excluded sound events.
     * <p>
     * Entries must use the full sound event ID:
     * minecraft:entity.generic.explode
     */
    public static void configure(String[] entries) {

        Set<String> updatedEntries = new HashSet<>();

        if (entries != null) {
            for (String entry : entries) {

                if (entry == null) {
                    continue;
                }

                String soundEvent = entry.trim();

                if (soundEvent.isEmpty()) {
                    continue;
                }

                /*
                 * Sound names passed by SoundPhysics have this format:
                 *
                 * namespace:event_id|sound/file/path
                 *
                 * Only the event ID is used for matching.
                 */
                int separator = soundEvent.indexOf('|');

                if (separator >= 0) {
                    soundEvent = soundEvent.substring(0, separator).trim();
                }

                if (!soundEvent.isEmpty()) {
                    updatedEntries.add(soundEvent);
                }
            }
        }

        excludedSoundEvents = Collections.unmodifiableSet(updatedEntries);
    }

    /**
     * Checks whether the specified sound event should bypass
     * reflected sound direction evaluation
     */
    public static boolean isExcluded(String soundName) {

        if (soundName == null || soundName.isEmpty()) {
            return false;
        }

        int separator = soundName.indexOf('|');

        String soundEvent = separator >= 0
                ? soundName.substring(0, separator)
                : soundName;

        return excludedSoundEvents.contains(soundEvent);
    }
}
