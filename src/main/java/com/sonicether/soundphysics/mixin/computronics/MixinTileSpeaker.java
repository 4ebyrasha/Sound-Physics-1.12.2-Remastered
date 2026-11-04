package com.sonicether.soundphysics.mixin.computronics;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.sonicether.soundphysics.SoundPhysics;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pl.asie.computronics.Computronics;
import pl.asie.computronics.tile.TileSpeaker;

@Mixin(value = TileSpeaker.class, remap = false)
public class MixinTileSpeaker {
    @ModifyReturnValue(method = "getSoundPos", at = @At("RETURN"))
    private Vec3d applyOffset(Vec3d origin) {
        return SoundPhysics.computronicsOffset(origin, (TileEntity)(Object)this, Computronics.speaker.rotation.FACING);
    }

}
