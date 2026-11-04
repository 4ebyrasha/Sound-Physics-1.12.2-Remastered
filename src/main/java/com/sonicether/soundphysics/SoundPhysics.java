package com.sonicether.soundphysics;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.*;
import paulscode.sound.*;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockTrapDoor;


@Mod(modid = SoundPhysics.modid, clientSideOnly = true, acceptedMinecraftVersions = SoundPhysics.mcVersion, version = Tags.VERSION, guiFactory = "com.sonicether.soundphysics.SPGuiFactory")
public class SoundPhysics {

	public static final String modid = "soundphysics";
	public static final String version = Tags.VERSION;
	public static final String mcVersion = "1.12.2";

	public static final Logger logger = LogManager.getLogger(modid);

	public static boolean onServer = false;

	public static SoundEvent CLICK = new SoundEvent(new ResourceLocation(modid, "gui_clicks"));

	private static final Pattern rainPattern = Pattern.compile(".*rain.*");
	private static final Pattern stepPattern = Pattern.compile(".*step.*");
	private static final Pattern blockPattern = Pattern.compile(".*block.*");
	private static final Pattern noteBlockPattern = Pattern.compile(".*block.note.*");

	private static final Pattern allPattern = Pattern.compile(Config.getBlacklist());

	@Mod.EventHandler
	public void preInit(final FMLPreInitializationEvent event) {
		Config.instance.preInit(event);
		ClientWorldSnapshotManager.register();
		SoundDirectionDebug.register();
	}

	@Mod.EventHandler
	public void init(final FMLInitializationEvent event) {
		Config.instance.init(event);
	}

	private static int auxFXSlot0;
	private static int auxFXSlot1;
	private static int auxFXSlot2;
	private static int auxFXSlot3;
	private static int reverb0;
	private static int reverb1;
	private static int reverb2;
	private static int reverb3;
	private static int directFilter0;
	private static int sendFilter0;
	private static int sendFilter1;
	private static int sendFilter2;
	private static int sendFilter3;
	private static int maxAuxSends;

	private static Minecraft mc;

	private static SoundCategory lastSoundCategory;
	private static String lastSoundName;
	private static ISound.AttenuationType lastSoundAtt;

	// THESE VARIABLES ARE CONSTANTLY ACCESSED AND USED BY ASM INJECTED CODE! DO
	// NOT REMOVE!
	public static int attenuationModel = SoundSystemConfig.ATTENUATION_ROLLOFF;
	public static float globalRolloffFactor = Config.rolloffFactor;
	public static float globalVolumeMultiplier = Config.globalVolumeMultiplier; // Global volume multiplier.
	public static float globalReverbMultiplier = 0.7f * Config.globalReverbGain;
	public static double soundDistanceAllowance = Config.soundDistanceAllowance;

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void init(SoundSystem snds) {
		mc = Minecraft.getMinecraft();
		try {
			setupEFX();
		} catch (Throwable e) {
			logError("Failed to init EFX");
			logError(e.toString());
		}
	}

	public static void applyConfigChanges() {
		globalRolloffFactor = Config.rolloffFactor;
		globalReverbMultiplier = 0.7f * Config.globalReverbGain;
		soundDistanceAllowance = Config.soundDistanceAllowance;
		globalVolumeMultiplier = Config.globalVolumeMultiplier;

		if (auxFXSlot0 != 0) {
			// Set the global reverb parameters and apply them to the effect and
			// effectslot
			setReverbParams(ReverbParams.getReverb0(), auxFXSlot0, reverb0);
			setReverbParams(ReverbParams.getReverb1(), auxFXSlot1, reverb1);
			setReverbParams(ReverbParams.getReverb2(), auxFXSlot2, reverb2);
			setReverbParams(ReverbParams.getReverb3(), auxFXSlot3, reverb3);
		}
	}

	private static void setupEFX() {
		// Get current context and device
		final ALCcontext currentContext = ALC10.alcGetCurrentContext();
		final ALCdevice currentDevice = ALC10.alcGetContextsDevice(currentContext);

		if (ALC10.alcIsExtensionPresent(currentDevice, "ALC_EXT_EFX")) {
			log("EFX Extension recognized.");
		} else {
			logError("EFX Extension not found on current device. Aborting.");
			return;
		}
		IntBuffer buffer = BufferUtils.createIntBuffer(Short.MAX_VALUE + 1);
		ALC10.alcGetInteger(currentDevice, EFX10.ALC_MAX_AUXILIARY_SENDS, buffer);
		maxAuxSends = buffer.get();
		log("Max auxiliary sends: " + maxAuxSends);

		// Create auxiliary effect slots
		auxFXSlot0 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot0 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot0, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

		auxFXSlot1 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot1 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot1, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

		auxFXSlot2 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot2 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot2, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

		auxFXSlot3 = EFX10.alGenAuxiliaryEffectSlots();
		log("Aux slot " + auxFXSlot3 + " created");
		EFX10.alAuxiliaryEffectSloti(auxFXSlot3, EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);
		checkErrorLog("Failed creating auxiliary effect slots!");

		reverb0 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb0, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 0!");
		reverb1 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb1, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 1!");
		reverb2 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb2, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 2!");
		reverb3 = EFX10.alGenEffects();
		EFX10.alEffecti(reverb3, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_EAXREVERB);
		checkErrorLog("Failed creating reverb effect slot 3!");

		// Create filters
		directFilter0 = EFX10.alGenFilters();
		EFX10.alFilteri(directFilter0, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter0 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter0, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter1 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter1, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter2 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter2, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);

		sendFilter3 = EFX10.alGenFilters();
		EFX10.alFilteri(sendFilter3, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);
		checkErrorLog("Error creating lowpass filters!");

		applyConfigChanges();
	}

	private static SoundCategory getSoundCategory(final SoundCategory sc, final String name) {
		if (Config.noteBlockEnable && sc == SoundCategory.RECORDS && noteBlockPattern.matcher(name).matches()) {
			return SoundCategory.BLOCKS;
		} else {
			return sc;
		}
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void setLastSound(final ISound snd, final SoundCategory sc, final ResourceLocation soundRes, final ResourceLocation eventRes) {
		lastSoundName = eventRes.toString()+"|"+soundRes.getPath(); // Quick and dirty hack to check the event and sound name
		lastSoundCategory = getSoundCategory(sc,lastSoundName);
		lastSoundAtt = snd.getAttenuationType();
		if (snd instanceof MovingSound)               // Hacky fix until i properly do moving sounds (I'm currently thinking about how)
			lastSoundCategory = SoundCategory.RECORDS;// because all (at least vanilla) moving sounds don't init their position when played
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static float applyGlobalVolumeMultiplier(final float volume) {
		if (!Config.volumeMulOnlyAffected || !(mc.player == null || mc.world == null || lastSoundCategory == SoundCategory.MASTER ||
				lastSoundAtt == ISound.AttenuationType.NONE || lastSoundCategory == SoundCategory.RECORDS || lastSoundCategory == SoundCategory.MUSIC)) {
			return volume * globalVolumeMultiplier;
		} else {
			return volume;
		}
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	// For sounds that get played normally
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, lastSoundCategory, lastSoundName, lastSoundAtt);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	// For sounds that get played using OpenAL directly or just not using the minecraft sound system
	public static void onPlaySoundAL(final float posX, final float posY, final float posZ, final int sourceID) {
		onPlaySound(posX, posY, posZ, sourceID, SoundCategory.AMBIENT, "openal", ISound.AttenuationType.LINEAR);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static void onPlaySound(final float posX, final float posY, final float posZ, final int sourceID, SoundCategory soundCat, String soundName, ISound.AttenuationType attType) {
		//log(String.valueOf(posX)+" "+String.valueOf(posY)+" "+String.valueOf(posZ)+" - "+String.valueOf(sourceID)+" - "+soundCat.toString()+" - "+attType.toString()+" - "+soundName);
		evaluateEnvironment(sourceID, posX, posY, posZ, soundCat, soundName, attType);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static SoundBuffer onLoadSound(SoundBuffer buff, String filename) {
		if (buff == null || buff.audioFormat.getChannels() == 1 || !Config.autoSteroDownmix) return buff;
		if (mc == null || mc.player == null || mc.world == null || lastSoundCategory == SoundCategory.RECORDS || lastSoundCategory == SoundCategory.MUSIC ||
				allPattern.matcher(filename).matches()) {
			if (Config.autoSteroDownmixLogging) log("Not converting sound '"+filename+"'("+buff.audioFormat.toString()+")");
			return buff;
		}
		AudioFormat orignalformat = buff.audioFormat;
		int bits = orignalformat.getSampleSizeInBits();
		boolean bigendian = orignalformat.isBigEndian();
		AudioFormat monoformat = new AudioFormat(orignalformat.getEncoding(), orignalformat.getSampleRate(), bits,
				1, orignalformat.getFrameSize(), orignalformat.getFrameRate(), bigendian);
		if (Config.autoSteroDownmixLogging) log("Converting sound '"+filename+"'("+ orignalformat +") to mono ("+ monoformat +")");

		ByteBuffer bb = ByteBuffer.wrap(buff.audioData,0,buff.audioData.length);
		bb.order(bigendian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		if (bits == 8) {
			for (int i = 0; i < buff.audioData.length; i+=2) {
				bb.put(i/2,(byte)((bb.get(i)+bb.get(i+1))/2));
			}
		} else if (bits == 16) {
			for (int i = 0; i < buff.audioData.length; i+=4) {
				bb.putShort((i/2),(short)((bb.getShort(i)+bb.getShort(i+2))/2));
			}
		}
		buff.audioFormat = monoformat;
		buff.trimData(buff.audioData.length/2);
		return buff;
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static double calculateEntitySoundOffset(final Entity entity, final SoundEvent sound) {
		if (sound == null) return entity.getEyeHeight();
		if (stepPattern.matcher(sound.soundName.getPath()).matches()) {
			return 0;
		}

		return entity.getEyeHeight();
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static Vec3d calculateEntitySoundOffsetVec(final Vec3d pos, final Entity entity, final SoundEvent sound) {
		return new Vec3d(pos.x,pos.y+calculateEntitySoundOffset(entity,sound),pos.z);
	}

	// Copy of isRainingAt
	private static boolean isSnowingAt(BlockPos position, boolean check_rain, ClientWorldSnapshot worldSnapshot) {
		if ((check_rain && !worldSnapshot.isRaining()) || !worldSnapshot.canSeeSky(position) ||
				worldSnapshot.getPrecipitationHeight(position).getY() > position.getY()) {
			return false;
		} else {
			return worldSnapshot.canSnowAt(position) || worldSnapshot.getBiome(position).getEnableSnow();
		}
	}

	private static boolean isPlayerInsideOfMaterial(final Material material, final ClientWorldSnapshot worldSnapshot) {
		if (mc.player.getRidingEntity() instanceof EntityBoat) {
			return false;
		}
		final double eyeY = mc.player.posY + getPlayerEyeHeight();
		final BlockPos eyePos = new BlockPos(mc.player.posX, eyeY, mc.player.posZ);
		return worldSnapshot.getBlockState(eyePos).getMaterial() == material;
	}

	@SuppressWarnings("deprecation")
	private static float getBlockReflectivity(final BlockPos blockPos, final ClientWorldSnapshot worldSnapshot) {
		final Block block = worldSnapshot.getBlockState(blockPos).getBlock();
		final SoundType soundType = block.getSoundType();

		float reflectivity = 0.5f;

		if (soundType == SoundType.STONE) {
			reflectivity = Config.stoneReflectivity;
		} else if (soundType == SoundType.WOOD) {
			reflectivity = Config.woodReflectivity;
		} else if (soundType == SoundType.GROUND) {
			reflectivity = Config.groundReflectivity;
		} else if (soundType == SoundType.PLANT) {
			reflectivity = Config.plantReflectivity;
		} else if (soundType == SoundType.METAL) {
			reflectivity = Config.metalReflectivity;
		} else if (soundType == SoundType.GLASS) {
			reflectivity = Config.glassReflectivity;
		} else if (soundType == SoundType.CLOTH) {
			reflectivity = Config.clothReflectivity;
		} else if (soundType == SoundType.SAND) {
			reflectivity = Config.sandReflectivity;
		} else if (soundType == SoundType.SNOW) {
			reflectivity = Config.snowReflectivity;
		} else if (soundType == SoundType.LADDER) {
			reflectivity = Config.woodReflectivity;
		} else if (soundType == SoundType.ANVIL) {
			reflectivity = Config.metalReflectivity;
		}

		reflectivity *= Config.globalBlockReflectance;

		return reflectivity;
	}

	private static Vec3d getNormalFromFacing(final EnumFacing sideHit) {
		return new Vec3d(sideHit.getDirectionVec());
	}

	/**
	 * Checks whether a position shares unobstructed airspace with the listener.
	 * <p>
	 * Missing chunk data is treated as unknown, not as open air.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			Vec3d soundPosition,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot
	) {
		return getSharedAirspace(
				soundPosition,
				listenerPosition,
				worldSnapshot,
				null,
				SoundDirectionDebug.COLOR_VALID
		);
	}

	/**
	 * Checks whether a position shares unobstructed airspace with the listener
	 * and optionally records the tested path for the direction debug overlay.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			Vec3d soundPosition,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot,
			SoundDirectionDebug.Builder debug,
			int clearColor
	) {
		if (worldSnapshot == null) {
			return null;
		}

		if (!worldSnapshot.hasChunkCoverage(soundPosition, listenerPosition)) {
			if (debug != null) {
				debug.addLine(
						soundPosition,
						listenerPosition,
						SoundDirectionDebug.COLOR_UNKNOWN
				);
			}
			return null;
		}

		final RayTraceResult hit = worldSnapshot.rayTraceBlocks(
				soundPosition,
				listenerPosition,
				true
		);

		if (hit == null) {
			if (debug != null) {
				debug.addLine(soundPosition, listenerPosition, clearColor);
			}
			return soundPosition.subtract(listenerPosition);
		}

		if (debug != null) {
			debug.addLine(
					soundPosition,
					hit.hitVec,
					SoundDirectionDebug.COLOR_BLOCKED
			);
		}

		return null;
	}

	/**
	 * Checks shared airspace starting from a block reflection point.
	 * <p>
	 * The ray is slightly offset along the surface normal to avoid
	 * immediately colliding with the same block.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			RayTraceResult hit,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot
	) {
		return getSharedAirspace(
				hit,
				listenerPosition,
				worldSnapshot,
				null
		);
	}

	/**
	 * Checks shared airspace starting from a block reflection point and
	 * optionally records the tested path for the direction debug overlay.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			RayTraceResult hit,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot,
			SoundDirectionDebug.Builder debug
	) {
		if (hit == null || hit.sideHit == null || hit.hitVec == null) {
			return null;
		}

		final Vec3d normal = getNormalFromFacing(hit.sideHit);

		final Vec3d rayStart = hit.hitVec.add(
				normal.x * 0.01D,
				normal.y * 0.01D,
				normal.z * 0.01D
		);

		return getSharedAirspace(
				rayStart,
				listenerPosition,
				worldSnapshot,
				debug,
				SoundDirectionDebug.COLOR_VALID
		);
	}

	/**
	 * Changes the actual OpenAL source position.
	 */
	private static void setSoundPos(
			int sourceID,
			Vec3d position
	) {
		AL10.alSource3f(
				sourceID,
				AL10.AL_POSITION,
				(float) position.x,
				(float) position.y,
				(float) position.z
		);
	}

	private static Vec3d reflect(final Vec3d dir, final Vec3d normal) {
		final double dot2 = dir.dotProduct(normal) * 2;

		final double x = dir.x - dot2 * normal.x;
		final double y = dir.y - dot2 * normal.y;
		final double z = dir.z - dot2 * normal.z;

		return new Vec3d(x, y, z);
	}

	private static Vec3d offsetSoundByName(final double soundX, final double soundY, final double soundZ,
										   final Vec3d playerPos, final String name, final SoundCategory category,
										   final ClientWorldSnapshot worldSnapshot) {
		double offsetX = 0.0;
		double offsetY = 0.0;
		double offsetZ = 0.0;
		double offsetTowardsPlayer = 0.0;

		double tempNormX = 0;
		double tempNormY = 0;
		double tempNormZ = 0;

		if (soundY % 1.0 < 0.001 || stepPattern.matcher(name).matches()) {
			offsetY = 0.225;
		}

		if ((category == SoundCategory.BLOCKS || blockPattern.matcher(name).matches() ||
				(name.equals("openal") && !worldSnapshot.isAirBlock(new BlockPos(soundX,soundY,soundZ)))) &&
				(MathHelper.floor(playerPos.x) != MathHelper.floor(soundX) ||
						MathHelper.floor(playerPos.y) != MathHelper.floor(soundY) ||
						MathHelper.floor(playerPos.z) != MathHelper.floor(soundZ))) {
			// The ray will probably hit the block that it's emitting from
			// before
			// escaping. Offset the ray start position towards the player by the
			// diagonal half-length of a cube

			tempNormX = playerPos.x - soundX;
			tempNormY = playerPos.y - soundY;
			tempNormZ = playerPos.z - soundZ;
			final double length = Math.sqrt(tempNormX * tempNormX + tempNormY * tempNormY + tempNormZ * tempNormZ);
			tempNormX /= length;
			tempNormY /= length;
			tempNormZ /= length;
			// 0.867 > square root of 0.5^2 * 3
			offsetTowardsPlayer = 0.867;
			offsetX += tempNormX * offsetTowardsPlayer;
			offsetY += tempNormY * offsetTowardsPlayer;
			offsetZ += tempNormZ * offsetTowardsPlayer;
		}

		return new Vec3d(soundX + offsetX, soundY + offsetY, soundZ + offsetZ);
	}

	public static float getPlayerEyeHeight() throws IllegalStateException {
		ReentrantReadWriteLock lock = (ReentrantReadWriteLock)mc.player.getDataManager().lock;
		if (lock.isWriteLocked()) {
			return mc.player.getDefaultEyeHeight();
			//logError("Deadlock detected, avoiding it by throwing exception");
			//throw new IllegalStateException("Player's Data Mananger is write locked");
		}
		return mc.player.getEyeHeight();
	}

	/**
	 * Calculates direct sound occlusion using multiple slightly offset ray paths.
	 * The path with the lowest accumulated block occlusion is used, allowing
	 * sound to pass through small openings that a single ray could miss.
	 */
	private static float calculateOcclusion(final Vec3d soundPos, final Vec3d playerPos,
											final SoundCategory category, final String name,
											final ClientWorldSnapshot worldSnapshot) {
		if (Config.strictOcclusion) {
			return runOcclusion(soundPos, playerPos, worldSnapshot);
		}

		float variationFactor = Config.occlusionVariation;

		final boolean isBlockSound = category == SoundCategory.BLOCKS ||
				blockPattern.matcher(name).matches();

		if (isBlockSound) {
			variationFactor = Math.max(variationFactor, 0.49f);
		}

		float minOcclusion = runOcclusion(soundPos, playerPos, worldSnapshot);

		if (variationFactor <= 0.0f) {
			return minOcclusion;
		}

		for (int x = -1; x <= 1; x += 2) {
			for (int y = -1; y <= 1; y += 2) {
				for (int z = -1; z <= 1; z += 2) {
					final Vec3d offset = new Vec3d(
							x * variationFactor,
							y * variationFactor,
							z * variationFactor
					);

					final float occlusion = runOcclusion(
							soundPos.add(offset),
							playerPos.add(offset),
							worldSnapshot
					);

					minOcclusion = Math.min(minOcclusion, occlusion);
				}
			}
		}

		return minOcclusion;
	}

	/**
	 * Returns the occlusion contribution of a block.
	 * Door-like blocks are handled according to their open state:
	 * closed doors obstruct sound, while open doors and gates contribute
	 * no additional occlusion.
	 */
	private static float getBlockOcclusion(final BlockPos blockPos,
										   final EnumFacing sideHit,
										   final ClientWorldSnapshot worldSnapshot) {
		final IBlockState blockState = worldSnapshot.getBlockState(blockPos);
		final Block block = blockState.getBlock();
		final Material material = blockState.getMaterial();

		if (block == Blocks.JUKEBOX) {
			return 0.0f;
		}

		if (block instanceof BlockDoor) {
			if (blockState.getValue(BlockDoor.OPEN)) {
				return 0.0f;
			}

			return 2.0f;
		}

		if (block instanceof BlockFenceGate) {
			if (blockState.getValue(BlockFenceGate.OPEN)) {
				return 0.0f;
			}

			return 1.5f;
		}

		if (block instanceof BlockTrapDoor) {
			if (blockState.getValue(BlockTrapDoor.OPEN)) {
				return 0.0f;
			}

			return 1.5f;
		}

		float blockOcclusion;

		if (material == Material.WATER) {
			blockOcclusion = 0.25f;
		} else if (material == Material.LAVA) {
			blockOcclusion = 0.75f;
		} else {
			final SoundType soundType = block.getSoundType();

			if (soundType == SoundType.CLOTH) {
				blockOcclusion = 1.5f;
			} else if (soundType == SoundType.GLASS) {
				blockOcclusion = 0.1f;
			} else if (soundType == SoundType.SNOW) {
				blockOcclusion = 0.1f;
			} else if (soundType == SoundType.PLANT
					|| soundType == SoundType.LADDER) {
				blockOcclusion = 0.0f;
			} else {
				blockOcclusion = Config.defaultBlockOcclusionFactor;
			}
		}

		if (!block.isSideSolid(blockState, worldSnapshot, blockPos, sideHit)) {
			blockOcclusion *= Config.nonFullBlockOcclusionFactor;
		}

		return blockOcclusion;
	}

	/**
	 * Traces one path from the sound source to the listener and accumulates
	 * the occlusion contribution of every block intersected by that path.
	 */

	private static float runOcclusion(
			final Vec3d soundPos,
			final Vec3d playerPos,
			final ClientWorldSnapshot worldSnapshot
	) {
		if (Config.maxOcclusion <= 0.0f) {
			return 0.0f;
		}

		final Vec3d normalToPlayer =
				playerPos.subtract(soundPos).normalize();

		Vec3d rayOrigin = soundPos;

		float occlusionAccumulation = 0.0f;

		for (int i = 0; i < Config.maxOcclusionRays; i++) {

			final RayTraceResult rayHit =
					worldSnapshot.rayTraceBlocks(
							rayOrigin,
							playerPos,
							true
					);

			if (rayHit == null) {

				// Unobstructed remainder of the occlusion ray.
				SoundDirectionDebug.addOcclusionRay(
						mc.world,
						rayOrigin,
						playerPos,
						SoundDirectionDebug.COLOR_VALID
				);

				break;
			}

			// Visualize the segment that intersects a block.
			// Occlusion rays are rendered through walls.
			SoundDirectionDebug.addOcclusionRay(
					mc.world,
					rayOrigin,
					rayHit.hitVec,
					SoundDirectionDebug.COLOR_BLOCKED
			);

			final BlockPos blockPos = rayHit.getBlockPos();

			occlusionAccumulation += getBlockOcclusion(
					blockPos,
					rayHit.sideHit,
					worldSnapshot
			);

			if (occlusionAccumulation >= Config.maxOcclusion) {
				return Config.maxOcclusion;
			}

			rayOrigin = new Vec3d(
					rayHit.hitVec.x + normalToPlayer.x * 0.1,
					rayHit.hitVec.y + normalToPlayer.y * 0.1,
					rayHit.hitVec.z + normalToPlayer.z * 0.1
			);
		}

		return Math.min(
				occlusionAccumulation,
				Config.maxOcclusion
		);
	}


	private static void evaluateEnvironment(final int sourceID, final float posX, final float posY, final float posZ, final SoundCategory category,
											final String name, ISound.AttenuationType attType) {
		try {
			final ClientWorldSnapshot worldSnapshot = ClientWorldSnapshotManager.getSnapshot();

			if (mc.player == null || mc.world == null || worldSnapshot == null ||
					category == SoundCategory.MASTER || attType == ISound.AttenuationType.NONE ||
					category == SoundCategory.RECORDS || category == SoundCategory.MUSIC) {
				// posY <= 0 as a condition has to be there: Ingame
				// menu clicks do have a player and world present
				// The Y position check has been removed due to problems with Cubic Chunks
				setEnvironment(sourceID, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
				return;
			}

			final boolean isRain = rainPattern.matcher(name).matches();

			if (Config.skipRainOcclusionTracing && isRain) {
				setEnvironment(sourceID, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
				return;
			}

			float directCutoff = 1.0f;
			final float absorptionCoeff = Config.globalBlockAbsorption * 3.0f;

			final Vec3d playerPos = new Vec3d(mc.player.posX, mc.player.posY + getPlayerEyeHeight(), mc.player.posZ);
			final Vec3d soundPos = offsetSoundByName(posX, posY, posZ, playerPos, name, category, worldSnapshot);

			final SoundDirectionDebug.Builder directionDebug =
					SoundDirectionDebug.begin(
							mc.world,
							soundPos,
							playerPos,
							name
					);

			float airAbsorptionFactor = 1.0f;

			if (Config.snowAirAbsorptionFactor > 1.0f && worldSnapshot.isRaining()) {
				final Vec3d middlePos = playerPos.add(soundPos).scale(0.5);
				final BlockPos playerPosBlock = new BlockPos(playerPos);
				final BlockPos soundPosBlock = new BlockPos(soundPos);
				final BlockPos middlePosBlock = new BlockPos(middlePos);
				final int snowingPlayer = isSnowingAt(playerPosBlock,false,worldSnapshot) ? 1 : 0;
				final int snowingSound = isSnowingAt(soundPosBlock,false,worldSnapshot) ? 1 : 0;
				final int snowingMiddle = isSnowingAt(middlePosBlock,false,worldSnapshot) ? 1 : 0;
				final float snowFactor = snowingPlayer * 0.25f + snowingMiddle * 0.5f + snowingSound * 0.25f;
				airAbsorptionFactor = Math.max(Config.snowAirAbsorptionFactor*worldSnapshot.getRainStrength()*snowFactor,airAbsorptionFactor);
			}

			final float occlusionAccumulation = calculateOcclusion(soundPos, playerPos, category, name, worldSnapshot);

			directCutoff = (float) Math.exp(-occlusionAccumulation * absorptionCoeff);
			float directGain = (float) Math.pow(directCutoff, 0.1);

			// Calculate reverb parameters for this sound
			float sendGain0 = 0.0f;
			float sendGain1 = 0.0f;
			float sendGain2 = 0.0f;
			float sendGain3 = 0.0f;

			float sendCutoff0 = 1.0f;
			float sendCutoff1 = 1.0f;
			float sendCutoff2 = 1.0f;
			float sendCutoff3 = 1.0f;

			if (isPlayerInsideOfMaterial(Material.WATER, worldSnapshot)) {
				directCutoff *= 1.0f - Config.underwaterFilter;
			}

			if (isRain) {
				setEnvironment(sourceID, sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2,
						sendCutoff3, directCutoff, directGain, airAbsorptionFactor);
				return;
			}

			// Shoot rays around sound
			final float phi = 1.618033988f;
			final float gAngle = phi * (float) Math.PI * 2.0f;
			final float maxDistance = Config.maxDistance;

			final int numRays = Config.environmentEvaluationRays;
			final int rayBounces = 4;

			final float[] bounceReflectivityRatio = new float[rayBounces];

			float sharedAirspace = 0.0f;

			// Reflected sound direction evaluation
			final ReflectedAudio reflectedAudio =
					new ReflectedAudio(occlusionAccumulation);

			// Check whether the original sound position has direct visibility.
			// This also records whether the path is clear, blocked, or unknown
			// because one or more chunks have not been loaded yet.
			if (reflectedAudio.shouldEvaluateDirection()) {
				final Vec3d directSharedAirspaceVector = getSharedAirspace(
						soundPos,
						playerPos,
						worldSnapshot,
						directionDebug,
						SoundDirectionDebug.COLOR_DIRECT_CLEAR
				);

				if (directSharedAirspaceVector != null) {
					reflectedAudio.addDirectAirspace(directSharedAirspaceVector);
				}
			} else if (Config.debugSoundDirection) {
				// Keep the direct path visible in the debug overlay even when
				// direction redirection is disabled for this sound.
				getSharedAirspace(
						soundPos,
						playerPos,
						worldSnapshot,
						directionDebug,
						SoundDirectionDebug.COLOR_DIRECT_CLEAR
				);
			}

			final float rcpTotalRays = 1.0f / (numRays * rayBounces);
			final float rcpPrimaryRays = 1.0f / numRays;

			for (int i = 0; i < numRays; i++) {
				final float fiN = (float) i / numRays;
				final float longitude = gAngle * (float) i;
				final float latitude = (float) Math.asin(fiN * 2.0f - 1.0f);

				final Vec3d rayDir = new Vec3d(Math.cos(latitude) * Math.cos(longitude),
						Math.cos(latitude) * Math.sin(longitude), Math.sin(latitude));

				final Vec3d rayStart = new Vec3d(soundPos.x, soundPos.y, soundPos.z);

				final Vec3d rayEnd = new Vec3d(rayStart.x + rayDir.x * maxDistance, rayStart.y + rayDir.y * maxDistance,
						rayStart.z + rayDir.z * maxDistance);

				final boolean rayCoverage = worldSnapshot.hasChunkCoverage(rayStart, rayEnd);
				final RayTraceResult rayHit = worldSnapshot.rayTraceBlocks(rayStart, rayEnd, true);

				if (rayHit != null) {
					directionDebug.addLine(
							rayStart,
							rayHit.hitVec,
							SoundDirectionDebug.COLOR_PRIMARY
					);
				} else {
					directionDebug.addLine(
							rayStart,
							rayEnd,
							rayCoverage
									? SoundDirectionDebug.COLOR_PRIMARY
									: SoundDirectionDebug.COLOR_UNKNOWN
					);
				}

				if (rayHit != null) {
					final double rayLength = soundPos.distanceTo(rayHit.hitVec);

					// Additional bounces
					BlockPos lastHitBlock = rayHit.getBlockPos();
					Vec3d lastHitPos = rayHit.hitVec;
					Vec3d lastHitNormal = getNormalFromFacing(rayHit.sideHit);
					Vec3d lastRayDir = rayDir;

					float totalRayDistance = (float) rayLength;

                /* Check whether the first reflection point has
                    / an unobstructed path towards the listener
                */
					final Vec3d firstSharedAirspaceVector = getSharedAirspace(
							rayHit,
							playerPos,
							worldSnapshot,
							directionDebug
					);

					if (firstSharedAirspaceVector != null) {
						reflectedAudio.addSharedAirspace(
								firstSharedAirspaceVector,
								totalRayDistance
						);
					}

					// Secondary ray bounces
					for (int j = 0; j < rayBounces; j++) {
						final Vec3d newRayDir = reflect(lastRayDir, lastHitNormal);
						// Vec3d newRayDir = lastHitNormal;
						final Vec3d newRayStart = new Vec3d(lastHitPos.x + lastHitNormal.x * 0.01,
								lastHitPos.y + lastHitNormal.y * 0.01, lastHitPos.z + lastHitNormal.z * 0.01);
						final Vec3d newRayEnd = new Vec3d(newRayStart.x + newRayDir.x * maxDistance,
								newRayStart.y + newRayDir.y * maxDistance, newRayStart.z + newRayDir.z * maxDistance);

						final boolean bounceCoverage = worldSnapshot.hasChunkCoverage(
								newRayStart,
								newRayEnd
						);
						final RayTraceResult newRayHit = worldSnapshot.rayTraceBlocks(
								newRayStart,
								newRayEnd,
								true
						);

						directionDebug.addLine(
								newRayStart,
								newRayHit == null ? newRayEnd : newRayHit.hitVec,
								newRayHit != null || bounceCoverage
										? SoundDirectionDebug.COLOR_BOUNCE
										: SoundDirectionDebug.COLOR_UNKNOWN
						);

						float energyTowardsPlayer = 0.25f;
						final float blockReflectivity = getBlockReflectivity(lastHitBlock, worldSnapshot);
						energyTowardsPlayer *= blockReflectivity * 0.75f + 0.25f;

						if (newRayHit == null) {
							totalRayDistance += (float) lastHitPos.distanceTo(playerPos);
						} else {
							final double newRayLength = lastHitPos.distanceTo(newRayHit.hitVec);

							bounceReflectivityRatio[j] += blockReflectivity;

							totalRayDistance += (float) newRayLength;

							lastHitPos = newRayHit.hitVec;
							lastHitNormal = getNormalFromFacing(newRayHit.sideHit);
							lastRayDir = newRayDir;
							lastHitBlock = newRayHit.getBlockPos();

							// Cast one final ray towards the player. If it's
							// unobstructed, then the sound source and the player
							// share airspace.
							if (!Config.simplerSharedAirspaceSimulation || j == rayBounces - 1) {
								final Vec3d finalRayStart = new Vec3d(lastHitPos.x + lastHitNormal.x * 0.01,
										lastHitPos.y + lastHitNormal.y * 0.01, lastHitPos.z + lastHitNormal.z * 0.01);

								final Vec3d sharedAirspaceVector = getSharedAirspace(
										finalRayStart,
										playerPos,
										worldSnapshot,
										directionDebug,
										SoundDirectionDebug.COLOR_VALID
								);

								if (sharedAirspaceVector != null) {

									sharedAirspace += 1.0f;

									reflectedAudio.addSharedAirspace(
											sharedAirspaceVector,
											totalRayDistance
									);
								}
							}
						}

						final float reflectionDelay = (float) Math.max(totalRayDistance, 0.0) * 0.12f * blockReflectivity;

						final float cross0 = 1.0f - MathHelper.clamp(Math.abs(reflectionDelay - 0.0f), 0.0f, 1.0f);
						final float cross1 = 1.0f - MathHelper.clamp(Math.abs(reflectionDelay - 1.0f), 0.0f, 1.0f);
						final float cross2 = 1.0f - MathHelper.clamp(Math.abs(reflectionDelay - 2.0f), 0.0f, 1.0f);
						final float cross3 = MathHelper.clamp(reflectionDelay - 2.0f, 0.0f, 1.0f);

						sendGain0 += cross0 * energyTowardsPlayer * 6.4f * rcpTotalRays;
						sendGain1 += cross1 * energyTowardsPlayer * 12.8f * rcpTotalRays;
						sendGain2 += cross2 * energyTowardsPlayer * 12.8f * rcpTotalRays;
						sendGain3 += cross3 * energyTowardsPlayer * 12.8f * rcpTotalRays;

						// Nowhere to bounce off of, stop bouncing!
						if (newRayHit == null) {
							break;
						}
					}
				}

			}

			// Calculate the apparent sound position using reflected paths
			final Vec3d redirectedSoundPos = reflectedAudio.evaluateSoundPosition(
					soundPos,
					playerPos
			);

			if (redirectedSoundPos != null) {
				setSoundPos(sourceID, redirectedSoundPos);
			}

			if (Config.debugSoundDirection) {
				if (redirectedSoundPos != null) {
					logger.info(
							"[Sound Direction Debug] Original: {} | Redirected: {}",
							soundPos,
							redirectedSoundPos
					);
				} else {
					logger.info(
							"[Sound Direction Debug] No redirection. Source: {}",
							soundPos
					);
				}
			}

			directionDebug.finish(redirectedSoundPos);

			// log("total reflectivity ratio: " + totalReflectivityRatio);

			bounceReflectivityRatio[0] = bounceReflectivityRatio[0] / numRays;
			bounceReflectivityRatio[1] = bounceReflectivityRatio[1] / numRays;
			bounceReflectivityRatio[2] = bounceReflectivityRatio[2] / numRays;
			bounceReflectivityRatio[3] = bounceReflectivityRatio[3] / numRays;

			sharedAirspace *= 64.0f;

			if (Config.simplerSharedAirspaceSimulation) {
				sharedAirspace *= rcpPrimaryRays;
			} else {
				sharedAirspace *= rcpTotalRays;
			}

			final float sharedAirspaceWeight0 = MathHelper.clamp(sharedAirspace / 20.0f, 0.0f, 1.0f);
			final float sharedAirspaceWeight1 = MathHelper.clamp(sharedAirspace / 15.0f, 0.0f, 1.0f);
			final float sharedAirspaceWeight2 = MathHelper.clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);
			final float sharedAirspaceWeight3 = MathHelper.clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);

			sendCutoff0 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0f) * (1.0f - sharedAirspaceWeight0)
					+ sharedAirspaceWeight0;
			sendCutoff1 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0f) * (1.0f - sharedAirspaceWeight1)
					+ sharedAirspaceWeight1;
			sendCutoff2 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f) * (1.0f - sharedAirspaceWeight2)
					+ sharedAirspaceWeight2;
			sendCutoff3 = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f) * (1.0f - sharedAirspaceWeight3)
					+ sharedAirspaceWeight3;

			// attempt to preserve directionality when airspace is shared by
			// allowing some of the dry signal through but filtered
			final float averageSharedAirspace = (sharedAirspaceWeight0 + sharedAirspaceWeight1 + sharedAirspaceWeight2
					+ sharedAirspaceWeight3) * 0.25f;
			directCutoff = Math.max((float) Math.pow(averageSharedAirspace, 0.5) * 0.2f, directCutoff);

			directGain = (float) Math.pow(directCutoff, 0.1);

			sendGain1 *= bounceReflectivityRatio[1];
			sendGain2 *= (float) Math.pow(bounceReflectivityRatio[2], 3.0);
			sendGain3 *= (float) Math.pow(bounceReflectivityRatio[3], 4.0);

			sendGain0 = MathHelper.clamp(sendGain0, 0.0f, 1.0f);
			sendGain1 = MathHelper.clamp(sendGain1, 0.0f, 1.0f);
			sendGain2 = MathHelper.clamp(sendGain2 * 1.05f - 0.05f, 0.0f, 1.0f);
			sendGain3 = MathHelper.clamp(sendGain3 * 1.05f - 0.05f, 0.0f, 1.0f);

			sendGain0 *= (float) Math.pow(sendCutoff0, 0.1);
			sendGain1 *= (float) Math.pow(sendCutoff1, 0.1);
			sendGain2 *= (float) Math.pow(sendCutoff2, 0.1);
			sendGain3 *= (float) Math.pow(sendCutoff3, 0.1);

			if (isPlayerInsideOfMaterial(Material.WATER, worldSnapshot)) {
				sendCutoff0 *= 0.4f;
				sendCutoff1 *= 0.4f;
				sendCutoff2 *= 0.4f;
				sendCutoff3 *= 0.4f;
			}

			setEnvironment(sourceID, sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2,
					sendCutoff3, directCutoff, directGain, airAbsorptionFactor);
		} catch(Exception e) {
			logger.error("Error while evaluation environment:", e);
			setEnvironment(sourceID, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
		}
	}

	private static void setEnvironment(final int sourceID, final float sendGain0, final float sendGain1,
									   final float sendGain2, final float sendGain3, final float sendCutoff0, final float sendCutoff1,
									   final float sendCutoff2, final float sendCutoff3, final float directCutoff, final float directGain,
									   final float airAbsorptionFactor) {
		// Set reverb send filter values and set source to send to all reverb fx
		// slots
		if (maxAuxSends >= 4) {
			EFX10.alFilterf(sendFilter0, EFX10.AL_LOWPASS_GAIN, sendGain0);
			EFX10.alFilterf(sendFilter0, EFX10.AL_LOWPASS_GAINHF, sendCutoff0);
			AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot0, 3, sendFilter0);
		}
		if (maxAuxSends >= 3) {
			EFX10.alFilterf(sendFilter1, EFX10.AL_LOWPASS_GAIN, sendGain1);
			EFX10.alFilterf(sendFilter1, EFX10.AL_LOWPASS_GAINHF, sendCutoff1);
			AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot1, 2, sendFilter1);
		}
		if (maxAuxSends >= 2) {
			EFX10.alFilterf(sendFilter2, EFX10.AL_LOWPASS_GAIN, sendGain2);
			EFX10.alFilterf(sendFilter2, EFX10.AL_LOWPASS_GAINHF, sendCutoff2);
			AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot2, 1, sendFilter2);
		}
		if (maxAuxSends >= 1) {
			EFX10.alFilterf(sendFilter3, EFX10.AL_LOWPASS_GAIN, sendGain3);
			EFX10.alFilterf(sendFilter3, EFX10.AL_LOWPASS_GAINHF, sendCutoff3);
			AL11.alSource3i(sourceID, EFX10.AL_AUXILIARY_SEND_FILTER, auxFXSlot3, 0, sendFilter3);
		}

		EFX10.alFilterf(directFilter0, EFX10.AL_LOWPASS_GAIN, directGain);
		EFX10.alFilterf(directFilter0, EFX10.AL_LOWPASS_GAINHF, directCutoff);
		AL10.alSourcei(sourceID, EFX10.AL_DIRECT_FILTER, directFilter0);

		AL10.alSourcef(sourceID, EFX10.AL_AIR_ABSORPTION_FACTOR, MathHelper.clamp(Config.airAbsorption * airAbsorptionFactor,0.0f,10.0f));
		checkErrorLog("Error while setting environment for source: " + sourceID);
	}

	/**
	 * Applies the parameters in the enum ReverbParams to the main reverb
	 * effect.
	 */
	protected static void setReverbParams(final ReverbParams r, final int auxFXSlot, final int reverbSlot) {
		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DENSITY, r.density);
		checkErrorLog("Error while assigning reverb density: " + r.density);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DIFFUSION, r.diffusion);
		checkErrorLog("Error while assigning reverb diffusion: " + r.diffusion);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_GAIN, r.gain);
		checkErrorLog("Error while assigning reverb gain: " + r.gain);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_GAINHF, r.gainHF);
		checkErrorLog("Error while assigning reverb gainHF: " + r.gainHF);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DECAY_TIME, r.decayTime);
		checkErrorLog("Error while assigning reverb decayTime: " + r.decayTime);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_DECAY_HFRATIO, r.decayHFRatio);
		checkErrorLog("Error while assigning reverb decayHFRatio: " + r.decayHFRatio);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_REFLECTIONS_GAIN, r.reflectionsGain);
		checkErrorLog("Error while assigning reverb reflectionsGain: " + r.reflectionsGain);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_LATE_REVERB_GAIN, r.lateReverbGain);
		checkErrorLog("Error while assigning lateReverbGain: " + r.lateReverbGain);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_LATE_REVERB_DELAY, r.lateReverbDelay);
		checkErrorLog("Error while assigning lateReverbDelay: " + r.lateReverbDelay);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, r.airAbsorptionGainHF);
		checkErrorLog("Error while assigning airAbsorptionGainHF: " + r.airAbsorptionGainHF);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, r.roomRolloffFactor);
		checkErrorLog("Error while assigning roomRolloffFactor: " + r.roomRolloffFactor);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_ECHO_TIME, r.echoTime);
		checkErrorLog("Error while assigning echoTime: " + r.echoTime);

		EFX10.alEffectf(reverbSlot, EFX10.AL_EAXREVERB_ECHO_DEPTH, r.echoDepth);
		checkErrorLog("Error while assigning echoDepth: " + r.echoDepth);

		// Attach updated effect object
		EFX10.alAuxiliaryEffectSloti(auxFXSlot, EFX10.AL_EFFECTSLOT_EFFECT, reverbSlot);
		checkErrorLog("Error while assigning reverb effect slot: " + reverbSlot);
	}

	public static void log(final String message) {
		logger.info(message);
	}

	public static void logError(final String errorMessage) {
		logger.error(errorMessage);
	}

	@SubscribeEvent
	public void registerSound(RegistryEvent.Register<SoundEvent> event) {
		event.getRegistry().register(CLICK);
	}

	protected static boolean checkErrorLog(final String errorMessage) {
		final int error = AL10.alGetError();
		if (error == AL10.AL_NO_ERROR) {
			return false;
		}

		String errorName;

		switch (error) {
			case AL10.AL_INVALID_NAME:
				errorName = "AL_INVALID_NAME";
				break;
			case AL10.AL_INVALID_ENUM:
				errorName = "AL_INVALID_ENUM";
				break;
			case AL10.AL_INVALID_VALUE:
				errorName = "AL_INVALID_VALUE";
				break;
			case AL10.AL_INVALID_OPERATION:
				errorName = "AL_INVALID_OPERATION";
				break;
			case AL10.AL_OUT_OF_MEMORY:
				errorName = "AL_OUT_OF_MEMORY";
				break;
			default:
				errorName = Integer.toString(error);
				break;
		}

		logError(errorMessage + " OpenAL error " + errorName);
		return true;
	}

}
