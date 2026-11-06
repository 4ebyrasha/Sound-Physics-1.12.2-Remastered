
package com.sonicether.soundphysics.mixin.vanilla;

import com.sonicether.soundphysics.SoundPhysics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCdevice;
import org.lwjgl.openal.EFX10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

@Mixin(value = AL.class, remap = false)
public abstract class MixinALContext {

    private static final Logger LOGGER =
            LogManager.getLogger("SoundPhysics");

    private static final int MAX_REQUESTED_SENDS = 4;

    @ModifyArgs(
            method = "init(Ljava/lang/String;IIZZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(Lorg/lwjgl/openal/ALCdevice;Ljava/nio/IntBuffer;)Lorg/lwjgl/openal/ALCcontext;"
            )
    )
    private static void soundphysics$modifyContextAttributes(Args args) {

        final ALCdevice device = (ALCdevice) args.get(0);

        if (device == null) {
            return;
        }

        try {

            if (!ALC10.alcIsExtensionPresent(
                    device,
                    "ALC_EXT_EFX"
            )) {
                return;
            }

            ALC10.alcGetError(device);

            final IntBuffer maxSendsBuffer =
                    BufferUtils.createIntBuffer(1);

            ALC10.alcGetInteger(
                    device,
                    EFX10.ALC_MAX_AUXILIARY_SENDS,
                    maxSendsBuffer
            );

            final int queryError = ALC10.alcGetError(device);

            if (queryError != ALC10.ALC_NO_ERROR) {

                LOGGER.warn(
                        "Could not query OpenAL auxiliary sends. ALC error: {}",
                        queryError
                );

                return;
            }

            final int maxSupportedSends =
                    maxSendsBuffer.get(0);

            final int requestedSends = Math.min(
                    maxSupportedSends,
                    MAX_REQUESTED_SENDS
            );

            if (requestedSends <= 0) {
                return;
            }

            final IntBuffer originalAttributes =
                    (IntBuffer) args.get(1);

            final IntBuffer source = originalAttributes == null
                    ? null
                    : originalAttributes.duplicate();

            final int originalLimit =
                    source == null ? 0 : source.limit();

            final IntBuffer modifiedAttributes =
                    BufferUtils.createIntBuffer(originalLimit + 3);

            boolean auxiliarySendsAlreadyPresent = false;

            if (source != null) {

                source.position(0);

                while (source.hasRemaining()) {

                    final int attribute = source.get();

                    if (attribute == 0) {
                        break;
                    }

                    if (!source.hasRemaining()) {
                        break;
                    }

                    int value = source.get();

                    if (attribute == EFX10.ALC_MAX_AUXILIARY_SENDS) {

                        value = requestedSends;

                        auxiliarySendsAlreadyPresent = true;
                    }

                    modifiedAttributes.put(attribute);
                    modifiedAttributes.put(value);
                }
            }

            if (!auxiliarySendsAlreadyPresent) {

                modifiedAttributes.put(
                        EFX10.ALC_MAX_AUXILIARY_SENDS
                );

                modifiedAttributes.put(requestedSends);
            }

            modifiedAttributes.put(0);

            /*
             * Required before passing the buffer to LWJGL.
             * The native call reads from the current position.
             */
            modifiedAttributes.flip();

            args.set(1, modifiedAttributes);

            LOGGER.info(
                    "Requesting {} EFX auxiliary sends during initial OpenAL context creation.",
                    requestedSends
            );

        } catch (Throwable e) {

            LOGGER.warn(
                    "Could not modify OpenAL context attributes. Using original attributes.",
                    e
            );
        }
    }

    /**
     * Clears Java-side references before LWJGL destroys the OpenAL context.
     *
     * OpenAL automatically destroys the context-owned EFX objects.
     */
    @Inject(
            method = "destroy()V",
            at = @At("HEAD")
    )
    private static void soundphysics$beforeContextDestroy(
            CallbackInfo ci
    ) {
        SoundPhysics.onOpenALContextDestroy();
    }
}
