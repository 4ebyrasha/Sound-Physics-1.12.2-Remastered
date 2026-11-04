package com.sonicether.soundphysics.mixin.ic2exp;

import com.sonicether.soundphysics.SoundPhysics;
import ic2.core.audio.PositionSpec;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import paulscode.sound.SoundSystem;

import java.net.URL;

@Mixin(targets = "ic2.core.audio.AudioManagerClient", remap = false)
public class MixinAudioManagerClient {

    @Redirect(method = "playOnce(Ljava/lang/Object;Lic2/core/audio/PositionSpec;Ljava/lang/String;ZF)Ljava/lang/String;", at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;quickPlay(ZLjava/net/URL;Ljava/lang/String;ZFFFIF)Ljava/lang/String;"))
    public String redirectQuickPlay(SoundSystem instance, boolean priority, URL url, String identifier, boolean toLoop, float x, float y, float z, int attmodel, float distOrRoll) {
        return instance.quickPlay(priority, url, identifier, toLoop, x, y, z, SoundPhysics.attenuationModel, SoundPhysics.globalRolloffFactor);
    }

    @Inject(method = "playOnce(Ljava/lang/Object;Lic2/core/audio/PositionSpec;Ljava/lang/String;ZF)Ljava/lang/String;", at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;setVolume(Ljava/lang/String;F)V"))
    private void injectSetLastSound(Object obj, PositionSpec positionSpec, String soundFile, boolean priorized, float volume, CallbackInfoReturnable<String> cir) {
        SoundPhysics.setLastSound(positionSpec.ordinal(), soundFile);
    }

    @Redirect(method = "playOnce(Ljava/lang/Object;Lic2/core/audio/PositionSpec;Ljava/lang/String;ZF)Ljava/lang/String;", at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;setVolume(Ljava/lang/String;F)V"))
    public void setVolume(SoundSystem instance, String sourcename, float value) {
        instance.setVolume(sourcename, SoundPhysics.applyGlobalVolumeMultiplier(value));
    }
}
