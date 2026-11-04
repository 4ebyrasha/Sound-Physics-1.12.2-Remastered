package com.sonicether.soundphysics.mixin.glibysrc;

import com.llamalad7.mixinextras.sugar.Local;
import com.sonicether.soundphysics.SoundPhysics;
import net.gliby.voicechat.client.sound.ClientStream;
import net.gliby.voicechat.client.sound.thread.ThreadUpdateStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;
import paulscode.sound.SoundSystem;

@Mixin(value = ThreadUpdateStream.class, remap = false)
public class MixinThreadUpdateStream {

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;setVolume(Ljava/lang/String;F)V"))
    public void setVolume(@Coerce SoundSystem instance, String s, float v) {
        instance.setVolume(s, v * SoundPhysics.globalVolumeMultiplier0);
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;setAttenuation(Ljava/lang/String;I)V"))
    public void setAttenuation(@Coerce SoundSystem instance, String s, int i) {
        instance.setAttenuation(s, SoundPhysics.attenuationModel);
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;setDistOrRoll(Ljava/lang/String;F)V"))
    public void setDistOrRoll(@Coerce SoundSystem instance, String s, float f) {
        instance.setDistOrRoll(s, SoundPhysics.globalRolloffFactor);
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;setPosition(Ljava/lang/String;FFF)V"))
    public void setPosition1(@Coerce SoundSystem instance, String s, float x, float y, float z, @Local ClientStream stream) {
        instance.setPosition(s, x, y + (float) SoundPhysics.calculateEntitySoundOffset(stream.player.getPlayer(), null), z);
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;setPosition(Ljava/lang/String;FFF)V"))
    public void setPosition2(@Coerce SoundSystem instance, String s, float x, float y, float z, @Local ClientStream stream) {
        instance.setPosition(s, x, y + (float) SoundPhysics.calculateEntitySoundOffset(Minecraft.getMinecraft().player, null), z);
    }
}
