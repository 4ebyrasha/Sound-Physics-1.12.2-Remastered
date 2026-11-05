package com.sonicether.soundphysics;

import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.client.audio.Sound;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nonnull;

public final class RedirectedPortalSound extends PositionedSound {

    private final Sound selectedSound;

    private final float portalVolume;

    private final float portalPitch;

    public RedirectedPortalSound(
            ISound originalSound,
            Sound selectedSound,
            Vec3d position,
            float volumeMultiplier
    ) {
        super(
                originalSound.getSoundLocation(),
                originalSound.getCategory()
        );

        this.selectedSound = selectedSound;

        this.positionedSoundLocation =
                originalSound.getSoundLocation();

        this.category =
                originalSound.getCategory();

        this.portalVolume =
                originalSound.getVolume() * volumeMultiplier;

        this.portalPitch =
                originalSound.getPitch();

        this.volume = portalVolume;
        this.pitch = portalPitch;

        this.xPosF = (float) position.x;
        this.yPosF = (float) position.y;
        this.zPosF = (float) position.z;

        this.repeat = false;
        this.repeatDelay = 0;

        this.attenuationType =
                originalSound.getAttenuationType();

        this.sound = selectedSound;
    }

    @Nonnull
    @Override
    public Sound getSound() {
        return selectedSound;
    }

    @Override
    public SoundEventAccessor createAccessor(
            SoundHandler handler
    ) {
        return handler.getAccessor(positionedSoundLocation);
    }

    @Override
    public float getVolume() {
        return portalVolume;
    }

    @Override
    public float getPitch() {
        return portalPitch;
    }

    @Override
    public boolean canRepeat() {
        return false;
    }

    @Override
    public int getRepeatDelay() {
        return 0;
    }
}
