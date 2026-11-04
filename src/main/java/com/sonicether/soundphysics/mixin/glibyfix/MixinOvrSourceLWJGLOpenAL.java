package com.sonicether.soundphysics.mixin.glibyfix;

import com.sonicether.soundphysics.SoundPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import paulscode.sound.Channel;
import paulscode.sound.FilenameURL;
import paulscode.sound.SoundBuffer;
import paulscode.sound.Source;
import ovr.paulscode.sound.libraries.ChannelLWJGLOpenAL;
import ovr.paulscode.sound.libraries.SourceLWJGLOpenAL;

@Mixin(value = SourceLWJGLOpenAL.class, remap = false)
public class MixinOvrSourceLWJGLOpenAL extends Source {

    @Shadow
    private ChannelLWJGLOpenAL channelOpenAL = null;

    public MixinOvrSourceLWJGLOpenAL(boolean priority, boolean toStream, boolean toLoop, String sourcename, FilenameURL filenameURL, SoundBuffer soundBuffer, float x, float y, float z, int attModel, float distOrRoll, boolean temporary) {
        super(priority, toStream, toLoop, sourcename, filenameURL, soundBuffer, x, y, z, attModel, distOrRoll, temporary);
    }

    @Inject(method = "play", at = @At(value = "INVOKE", target = "Lpaulscode/sound/Channel;play()V", shift = At.Shift.AFTER))
    private void injectPlay(Channel c, CallbackInfo ci) {
        SoundPhysics.onPlaySound(this.position.x, this.position.y, this.position.z, this.channelOpenAL.ALSource.get(0));
    }
}
