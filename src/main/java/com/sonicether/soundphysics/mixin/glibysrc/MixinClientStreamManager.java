package com.sonicether.soundphysics.mixin.glibysrc;

import com.llamalad7.mixinextras.sugar.Local;
import com.sonicether.soundphysics.SoundPhysics;
import net.gliby.voicechat.client.sound.ClientStreamManager;
import net.gliby.voicechat.client.sound.Datalet;
import net.gliby.voicechat.common.PlayerProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import paulscode.sound.SoundSystem;

import javax.sound.sampled.AudioFormat;

@Mixin(value = ClientStreamManager.class, remap = false)
public class MixinClientStreamManager {
    @Redirect(method = "createStream", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;rawDataStream(Ljavax/sound/sampled/AudioFormat;ZLjava/lang/String;FFFIF)V"))
    public void rawDataStream1(@Coerce SoundSystem instance, AudioFormat audioFormat, boolean b, String s, float x, float y, float z, int i, float f, @Local PlayerProxy player) {
        instance.rawDataStream(audioFormat, b, s, x, (float) (y + SoundPhysics.calculateEntitySoundOffset(player.getPlayer(), null)), z, SoundPhysics.attenuationModel, SoundPhysics.globalRolloffFactor);
    }

    @Redirect(method = "createStream", at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;rawDataStream(Ljavax/sound/sampled/AudioFormat;ZLjava/lang/String;FFFIF)V"))
    public void rawDataStream2(@Coerce SoundSystem instance, AudioFormat audioFormat, boolean b, String s, float x, float y, float z, int i, float f) {
        instance.rawDataStream(audioFormat, b, s, x, (float) (y + SoundPhysics.calculateEntitySoundOffset(Minecraft.getMinecraft().player, null)), z, SoundPhysics.attenuationModel, SoundPhysics.globalRolloffFactor);
    }

    @Redirect(method = "createStream", at = @At(value = "INVOKE",target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;setVolume(Ljava/lang/String;F)V"))
    public void setVolume(@Coerce SoundSystem instance, String s, float v) {
        instance.setVolume(s, v * SoundPhysics.globalVolumeMultiplier0);
    }

    @Inject(method = "giveStream", at = @At(value = "INVOKE", target = "Lnet/gliby/voicechat/client/sound/JitterBuffer;clearBuffer(I)V"))
    private void injectOnPlay(Datalet data, CallbackInfo ci, @Local String identifier) {
        SoundPhysics.onPlaySound(identifier);
    }
}
