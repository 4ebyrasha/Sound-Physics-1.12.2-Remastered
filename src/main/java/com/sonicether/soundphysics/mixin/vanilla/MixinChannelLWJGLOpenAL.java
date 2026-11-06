
package com.sonicether.soundphysics.mixin.vanilla;

import com.sonicether.soundphysics.SoundPhysics;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import paulscode.sound.libraries.ChannelLWJGLOpenAL;

import java.nio.IntBuffer;

@Mixin(value = ChannelLWJGLOpenAL.class, remap = false)
public abstract class MixinChannelLWJGLOpenAL {

    @Shadow
    public IntBuffer ALSource;

    @Inject(
            method = "cleanup()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/openal/AL10;alDeleteSources(Ljava/nio/IntBuffer;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void soundphysics$releaseSourceFilters(
            CallbackInfo ci
    ) {

        if (ALSource == null ||
                ALSource.limit() <= 0) {
            return;
        }

        SoundPhysics.onOpenALSourceDestroyed(
                ALSource.get(0)
        );
    }
}
