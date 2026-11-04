package com.sonicether.soundphysics.mixin.ic2c;

import com.sonicether.soundphysics.SoundPhysics;
import ic2.api.classic.audio.PositionSpec;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import paulscode.sound.SoundSystem;

import java.net.URL;

@Mixin(targets = "ic2.core.audio.AudioManagerClient", remap = false)
public class MixinAudioManagerClient {
    @Redirect(method = "playOnce(Ljava/lang/Object;Lic2/api/classic/audio/PositionSpec;Lnet/minecraft/util/ResourceLocation;ZF)V", at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;quickPlay(ZLjava/net/URL;Ljava/lang/String;ZFFFIF)Ljava/lang/String;"))
    public String redirectQuickPlay(SoundSystem instance, boolean priority, URL url, String identifier, boolean toLoop, float x, float y, float z, int attmodel, float distOrRoll) {
        return instance.quickPlay(priority, url, identifier, toLoop, x, y, z, SoundPhysics.attenuationModel, SoundPhysics.globalRolloffFactor);
    }

    @Inject(method = "playOnce(Ljava/lang/Object;Lic2/api/classic/audio/PositionSpec;Lnet/minecraft/util/ResourceLocation;ZF)V", at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;setVolume(Ljava/lang/String;F)V"))
    private void injectSetLastSound(Object obj, PositionSpec positionSpec, ResourceLocation soundFile, boolean priorized, float volume, CallbackInfo ci) {
        SoundPhysics.setLastSound(positionSpec.ordinal(), soundFile.getPath());
    }

    @Redirect(method = "playOnce(Ljava/lang/Object;Lic2/api/classic/audio/PositionSpec;Lnet/minecraft/util/ResourceLocation;ZF)V", at = @At(value = "INVOKE", target = "Lpaulscode/sound/SoundSystem;setVolume(Ljava/lang/String;F)V"))
    public void setVolume(SoundSystem instance, String sourcename, float value) {
        instance.setVolume(sourcename, SoundPhysics.applyGlobalVolumeMultiplier(value));
    }
}
