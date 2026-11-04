package com.sonicether.soundphysics.mixin.vanilla;

import com.sonicether.soundphysics.SoundPhysics;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(Entity.class)
public class MixinEntity {

    @Redirect(method = "playSound", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V"))
    private void calculateSound(World instance, EntityPlayer p_184148_1_, double p_184148_2_, double p_184148_4_, double p_184148_6_, SoundEvent p_184148_8_, SoundCategory p_184148_9_, float p_184148_10_, float p_184148_11_) {
        instance.playSound( p_184148_1_,  p_184148_2_,  p_184148_4_ + SoundPhysics.calculateEntitySoundOffset((Entity)(Object)this, p_184148_8_),  p_184148_6_,  p_184148_8_,  p_184148_9_,  p_184148_10_,  p_184148_11_);
    }

}
