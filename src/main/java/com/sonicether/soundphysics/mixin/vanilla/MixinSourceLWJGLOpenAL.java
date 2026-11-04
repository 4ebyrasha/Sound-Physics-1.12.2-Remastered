package com.sonicether.soundphysics.mixin.vanilla;

import com.sonicether.soundphysics.SoundPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import paulscode.sound.*;
import paulscode.sound.libraries.ChannelLWJGLOpenAL;
import paulscode.sound.libraries.SourceLWJGLOpenAL;

@Mixin(value = SourceLWJGLOpenAL.class, remap = false)
public class MixinSourceLWJGLOpenAL extends Source {

    @Shadow
    private ChannelLWJGLOpenAL channelOpenAL = null;

    public MixinSourceLWJGLOpenAL(boolean priority, boolean toStream, boolean toLoop, String sourcename, FilenameURL filenameURL, SoundBuffer soundBuffer, float x, float y, float z, int attModel, float distOrRoll, boolean temporary) {
        super(priority, toStream, toLoop, sourcename, filenameURL, soundBuffer, x, y, z, attModel, distOrRoll, temporary);
    }

    @Inject(method = "play", at = @At(value = "INVOKE", target = "Lpaulscode/sound/Channel;play()V", shift = At.Shift.AFTER))
    private void injectPlay(Channel c, CallbackInfo ci) {
        SoundPhysics.onPlaySound(this.position.x, this.position.y, this.position.z, this.channelOpenAL.ALSource.get(0));
    }
}
