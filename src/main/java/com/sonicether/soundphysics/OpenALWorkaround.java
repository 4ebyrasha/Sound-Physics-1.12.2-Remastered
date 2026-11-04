package com.sonicether.soundphysics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCcontext;
import org.lwjgl.openal.ALCdevice;
import org.lwjgl.openal.EFX10;

import java.lang.reflect.Field;
import java.nio.IntBuffer;

public final class OpenALWorkaround {

    private static final Logger LOGGER =
            LogManager.getLogger("SoundPhysics");

    private static final int AUXILIARY_SENDS = 4;

    private OpenALWorkaround() {
    }

    public static void requestFourAuxiliarySends() {
        final ALCcontext previousContext =
                ALC10.alcGetCurrentContext();

        if (previousContext == null) {
            return;
        }

        final ALCdevice device =
                ALC10.alcGetContextsDevice(previousContext);

        if (device == null ||
                !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
            return;
        }

        final IntBuffer attributes =
                BufferUtils.createIntBuffer(9);

        attributes.put(ALC10.ALC_FREQUENCY).put(44100);
        attributes.put(ALC10.ALC_REFRESH).put(60);
        attributes.put(ALC10.ALC_SYNC).put(ALC10.ALC_FALSE);
        attributes.put(EFX10.ALC_MAX_AUXILIARY_SENDS).put(AUXILIARY_SENDS);
        attributes.put(0);
        attributes.flip();

        final ALCcontext replacementContext =
                ALC10.alcCreateContext(device, attributes);

        if (replacementContext == null) {
            final int error = ALC10.alcGetError(device);

            LOGGER.warn(
                    "Could not create OpenAL context with {} auxiliary sends. ALC error: {}",
                    AUXILIARY_SENDS,
                    error
            );

            return;
        }

        if (ALC10.alcMakeContextCurrent(replacementContext)
                == ALC10.ALC_FALSE) {

            final int error = ALC10.alcGetError(device);

            ALC10.alcDestroyContext(replacementContext);

            LOGGER.warn(
                    "Could not activate OpenAL context with {} auxiliary sends. ALC error: {}",
                    AUXILIARY_SENDS,
                    error
            );

            return;
        }

        try {
            final Field contextField =
                    AL.class.getDeclaredField("context");

            contextField.setAccessible(true);
            contextField.set(null, replacementContext);

        } catch (Exception e) {
            ALC10.alcMakeContextCurrent(previousContext);
            ALC10.alcDestroyContext(replacementContext);

            LOGGER.warn(
                    "Could not replace LWJGL OpenAL context. Keeping the original context.",
                    e
            );

            return;
        }

        ALC10.alcDestroyContext(previousContext);

        LOGGER.info(
                "OpenAL context recreated with {} auxiliary sends requested.",
                AUXILIARY_SENDS
        );
    }
}
