package com.sonicether.soundphysics.mixin.vanilla;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef;
import com.sonicether.soundphysics.SoundPhysics;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.server.management.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class MixinPlayerList {
    /*@ModifyVariable(method = "sendToAllNearExcept", at = @At(value = "HEAD"), index = 4, argsOnly = true)
    private double injectSendToAllNearExcept(double radius) {
        return radius * Math.sqrt(SoundPhysics.soundDistanceAllowance);
    }*/

    @Inject(method = "sendToAllNearExcept", at = @At("HEAD"))
    private void injectSendToAllNearExcept(EntityPlayer p_148543_1_, double p_148543_2_, double p_148543_4_, double p_148543_6_, double p_148543_8_, int p_148543_10_, Packet<?> p_148543_11_, CallbackInfo ci, @Local(ordinal = 3) LocalDoubleRef radius) {
        radius.set(p_148543_8_ * Math.sqrt(SoundPhysics.soundDistanceAllowance));
    }
}
