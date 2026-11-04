package com.sonicether.soundphysics;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SoundRateManager {

    private static final Map<String, AtomicInteger> SOUND_COUNTS =
            new ConcurrentHashMap<>();

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile Map<String, Integer> SOUND_LIMITS =
            Collections.emptyMap();

    private SoundRateManager() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.register(new SoundRateManager());
        }
    }

    public static void configure(String[] entries) {
        Map<String, Integer> newLimits = new HashMap<>();

        if (entries != null) {
            for (String entry : entries) {
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }

                int separator = entry.indexOf('=');

                if (separator <= 0) {
                    SoundPhysics.logger.warn(
                            "Invalid sound rate limit entry: {}",
                            entry
                    );
                    continue;
                }

                String soundId = entry.substring(0, separator).trim();
                String value = entry.substring(separator + 1).trim();

                try {
                    int limit = Integer.parseInt(value);

                    if (soundId.isEmpty() || limit < -1) {
                        SoundPhysics.logger.warn(
                                "Invalid sound rate limit entry: {}",
                                entry
                        );
                        continue;
                    }

                    newLimits.put(soundId, limit);

                } catch (NumberFormatException e) {
                    SoundPhysics.logger.warn(
                            "Invalid sound rate limit entry: {}",
                            entry
                    );
                }
            }
        }

        SOUND_LIMITS = Collections.unmodifiableMap(newLimits);

        SOUND_COUNTS.clear();
    }

    /**
     * Returns true when acoustic processing should be skipped.
     * <p>
     * The sound itself is NOT cancelled
     */
    public static boolean shouldSkip(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return false;
        }

        // The sound name contains:
        // eventResourceLocation|soundFilePath
        //
        // We count by SoundEvent ID, not individual sound files.
        int separator = soundName.indexOf('|');

        String soundId = separator >= 0
                ? soundName.substring(0, separator)
                : soundName;

        Integer maximum = SOUND_LIMITS.get(soundId);

        if (maximum == null || maximum < 0) {
            return false;
        }

        // Zero completely disables acoustic processing for this sound.
        if (maximum == 0) {
            return true;
        }

        AtomicInteger counter = SOUND_COUNTS.computeIfAbsent(
                soundId,
                key -> new AtomicInteger()
        );

        int currentCount = counter.getAndIncrement();

        return currentCount >= maximum;
    }

    /**
     * Reset sound counters once per client tick
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SOUND_COUNTS.clear();
        }
    }
}
