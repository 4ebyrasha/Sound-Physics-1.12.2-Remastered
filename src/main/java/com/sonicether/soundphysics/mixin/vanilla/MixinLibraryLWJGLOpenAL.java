package com.sonicether.soundphysics.mixin.vanilla;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.sonicether.soundphysics.SoundPhysics;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import paulscode.sound.FilenameURL;
import paulscode.sound.SoundBuffer;
import paulscode.sound.libraries.LibraryLWJGLOpenAL;

@Mixin(value = LibraryLWJGLOpenAL.class, remap = false)
public class MixinLibraryLWJGLOpenAL {

    @ModifyVariable(method = "loadSound(Lpaulscode/sound/FilenameURL;)Z", at = @At(value = "STORE", opcode = Opcodes.ASTORE))
    private SoundBuffer modifyBuffer(SoundBuffer buffer, @Local FilenameURL filenameURL ) {
        return SoundPhysics.onLoadSound(buffer, filenameURL.getFilename());
    }

    @Inject(method = "loadSound(Lpaulscode/sound/SoundBuffer;Ljava/lang/String;)Z", at = @At(value = "HEAD"))
    private void modifyBuffer2(SoundBuffer buffer, String identifier, CallbackInfoReturnable<Boolean> cir, @Local LocalRef<SoundBuffer> bufferLocalRef) {
        bufferLocalRef.set(SoundPhysics.onLoadSound(buffer, identifier));
    }
}
