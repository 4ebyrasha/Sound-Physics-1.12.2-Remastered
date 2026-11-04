package com.sonicether.soundphysics.mixin.glibysrc;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.sonicether.soundphysics.SoundPhysics;
import net.gliby.voicechat.common.networking.ServerDatalet;
import net.gliby.voicechat.common.networking.ServerStream;
import net.gliby.voicechat.common.networking.ServerStreamManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerStreamManager.class, remap = false)
public class MixinServerStreamManager {
    @Inject(method = "feedWithinEntityWithRadius", at = @At("HEAD"))
    private void modifyDistance(ServerStream stream, ServerDatalet voiceData, int distance, CallbackInfo ci, @Local LocalIntRef dis) {
        dis.set((int) (distance * Math.sqrt(SoundPhysics.soundDistanceAllowance)));
    }

}
