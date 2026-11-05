package com.sonicether.soundphysics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.Sound;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public final class PortalSoundScheduler {

    /*
     * Maximum number of sounds waiting for client-thread processing.
     */
    private static final int MAX_PENDING_SOUNDS = 32;

    /*
     * Maximum number of portal copies submitted during one client tick.
     */
    private static final int MAX_SOUNDS_PER_TICK = 8;

    /*
     * Delay between the original sound and the portal copy.
     * 2 ticks are approximately 100 ms at 20 TPS.
     */
    private static final int PLAY_DELAY_TICKS = 2;

    private static final Queue<PendingPortalSound> PENDING_SOUNDS =
            new ArrayDeque<>();

    private static final PortalSoundScheduler INSTANCE =
            new PortalSoundScheduler();

    private PortalSoundScheduler() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    /**
     * Can be called from the OpenAL/SoundSystem thread.
     * <p>
     * This method only places immutable playback data into a queue.
     * It does not access Minecraft's SoundHandler.
     */
    public static boolean enqueue(
            ISound originalSound,
            Sound selectedSound,
            Vec3d position,
            float volume
    ) {
        if (originalSound == null ||
                selectedSound == null ||
                position == null) {
            return false;
        }

        synchronized (PENDING_SOUNDS) {

            if (PENDING_SOUNDS.size() >= MAX_PENDING_SOUNDS) {
                SoundPhysics.logger.warn(
                        "Portal sound queue is full. " +
                                "Using the original single-source behavior."
                );

                return false;
            }

            PENDING_SOUNDS.offer(
                    new PendingPortalSound(
                            originalSound,
                            selectedSound,
                            position,
                            volume
                    )
            );
        }

        return true;
    }

    /**
     * Called when all sound sources are stopped or the world changes.
     */
    public static void clear() {
        synchronized (PENDING_SOUNDS) {
            PENDING_SOUNDS.clear();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.player == null || mc.world == null) {
            clear();
            return;
        }

        final List<PendingPortalSound> soundsToPlay =
                new ArrayList<>(MAX_SOUNDS_PER_TICK);

        /*
         * Take a limited batch from the queue.
         *
         * SoundHandler is never accessed while holding the queue lock.
         */
        synchronized (PENDING_SOUNDS) {

            for (int i = 0; i < MAX_SOUNDS_PER_TICK; i++) {

                PendingPortalSound pending =
                        PENDING_SOUNDS.poll();

                if (pending == null) {
                    break;
                }

                soundsToPlay.add(pending);
            }
        }

        for (PendingPortalSound pending : soundsToPlay) {

            try {

                RedirectedPortalSound portalSound =
                        new RedirectedPortalSound(
                                pending.originalSound,
                                pending.selectedSound,
                                pending.position,
                                pending.volume
                        );

                /*
                 * This call is now guaranteed to run on the client thread.
                 */
                mc.getSoundHandler().playDelayedSound(
                        portalSound,
                        PLAY_DELAY_TICKS
                );

            } catch (Throwable e) {

                SoundPhysics.logger.error(
                        "Failed to schedule redirected portal sound:",
                        e
                );
            }
        }
    }

    private static final class PendingPortalSound {

        private final ISound originalSound;

        private final Sound selectedSound;

        private final Vec3d position;

        private final float volume;

        private PendingPortalSound(
                ISound originalSound,
                Sound selectedSound,
                Vec3d position,
                float volume
        ) {
            this.originalSound = originalSound;
            this.selectedSound = selectedSound;
            this.position = position;
            this.volume = volume;
        }
    }
}
