package com.sonicether.soundphysics.mixin.computronics;

import com.llamalad7.mixinextras.sugar.Local;
import com.sonicether.soundphysics.SoundPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.asie.lib.audio.StreamingAudioPlayer;

@Mixin(value = StreamingAudioPlayer.class, remap = false)
public class MixinStreamingAudioPlayer {
    @Shadow private float distance;

    @Inject(method = "play(Ljava/lang/String;FFFF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alSourceQueueBuffers(ILjava/nio/IntBuffer;)V"))
    private void injectPlay(String id, float x, float y, float z, float rolloff, CallbackInfo ci, @Local(ordinal = 0) StreamingAudioPlayer.SourceEntry source) {
        SoundPhysics.onPlaySoundAL(x, y, z, source.src.get(0));
    }

    @Inject(method = "setHearing", at = @At("RETURN"))
    private void injectSetHear(float dist, float vol, CallbackInfo ci) {
        this.distance = (float) (dist * SoundPhysics.soundDistanceAllowance);
    }
}
