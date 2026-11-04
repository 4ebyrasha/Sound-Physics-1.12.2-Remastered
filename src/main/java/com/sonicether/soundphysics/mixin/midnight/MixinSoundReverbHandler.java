package com.sonicether.soundphysics.mixin.midnight;

import com.mushroom.midnight.client.SoundReverbHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SoundReverbHandler.class, remap = false)
public class MixinSoundReverbHandler {
    @Inject(method = "onPlaySound", at = @At("HEAD"), cancellable = true)
    private static void cancelModReverb(int soundId, CallbackInfo ci) {
        ci.cancel();
    }
}
