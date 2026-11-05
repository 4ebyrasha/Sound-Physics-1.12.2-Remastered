package com.sonicether.soundphysics;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.ITickableSound;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


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

	private static final float UNKNOWN_OCCLUSION = -1.0f;

	@Mod.EventHandler
	public void preInit(final FMLPreInitializationEvent event) {
		Config.instance.preInit(event);

		ClientWorldSnapshotManager.register();
		SoundRateManager.register();
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
	private static int maxAuxSends;

	private static boolean efxAvailable;

	private static volatile ALCcontext efxContext;

	private static volatile boolean reverbConfigDirty = true;

	private static final ConcurrentHashMap<
			ALCcontext,
			ConcurrentHashMap<Integer, SourceFilters>
			> SOURCE_FILTERS = new ConcurrentHashMap<>();

	private static Minecraft mc;

	private static SoundCategory lastSoundCategory;
	private static String lastSoundName;
	private static ISound.AttenuationType lastSoundAtt;
	private static final ConcurrentHashMap<Integer, Vec3d> SOUND_POSITION_OFFSETS =
			new ConcurrentHashMap<>();
	private static final Map<String, SoundSourceData> SOUND_SOURCES =
			new ConcurrentHashMap<>();



	private static final class SourceFilters {

		private final int directFilter;

		private final int sendFilter0;
		private final int sendFilter1;
		private final int sendFilter2;
		private final int sendFilter3;

		private SourceFilters() {

			int direct = 0;

			int send0 = 0;
			int send1 = 0;
			int send2 = 0;
			int send3 = 0;

			try {

				direct = createLowPassFilter();

				if (maxAuxSends >= 4) {
					send0 = createLowPassFilter();
				}

				if (maxAuxSends >= 3) {
					send1 = createLowPassFilter();
				}

				if (maxAuxSends >= 2) {
					send2 = createLowPassFilter();
				}

				if (maxAuxSends >= 1) {
					send3 = createLowPassFilter();
				}

			} catch (RuntimeException | Error e) {

				deleteFilterQuietly(direct);

				deleteFilterQuietly(send0);
				deleteFilterQuietly(send1);
				deleteFilterQuietly(send2);
				deleteFilterQuietly(send3);

				throw e;
			}

			directFilter = direct;

			sendFilter0 = send0;
			sendFilter1 = send1;
			sendFilter2 = send2;
			sendFilter3 = send3;
		}
	}



	private static final class SoundSourceData {

		private final SoundCategory category;
		private final String soundName;
		private final ISound.AttenuationType attenuationType;
		private final boolean moving;

		private volatile long lastEvaluationTick;
		private volatile boolean acousticsEnabled;

		private SoundSourceData(
				SoundCategory category,
				String soundName,
				ISound.AttenuationType attenuationType,
				boolean moving,
				long lastEvaluationTick
		) {
			this.category = category;
			this.soundName = soundName;
			this.attenuationType = attenuationType;
			this.moving = moving;
			this.lastEvaluationTick = lastEvaluationTick;
		}
	}

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

		globalReverbMultiplier =
				0.7f * Config.globalReverbGain;

		soundDistanceAllowance =
				Config.soundDistanceAllowance;

		globalVolumeMultiplier =
				Config.globalVolumeMultiplier;

		/*
		 * Configuration events can be fired from the client thread,
		 * where the OpenAL context is not necessarily current.
		 *
		 * Mark the reverb settings dirty and apply them when
		 * the correct OpenAL context becomes current.
		 */
		reverbConfigDirty = true;

		applyReverbConfigIfPossible();
	}

	/**
	 * Applies pending reverb configuration only when the OpenAL
	 * context owning the effect slots is current on this thread.
	 */
	private static void applyReverbConfigIfPossible() {

		if (!efxAvailable ||
				efxContext == null ||
				auxFXSlot0 == 0 ||
				!reverbConfigDirty) {
			return;
		}

		final ALCcontext currentContext =
				ALC10.alcGetCurrentContext();

		if (currentContext != efxContext) {
			return;
		}

		setReverbParams(
				ReverbParams.getReverb0(),
				auxFXSlot0,
				reverb0
		);

		setReverbParams(
				ReverbParams.getReverb1(),
				auxFXSlot1,
				reverb1
		);

		setReverbParams(
				ReverbParams.getReverb2(),
				auxFXSlot2,
				reverb2
		);

		setReverbParams(
				ReverbParams.getReverb3(),
				auxFXSlot3,
				reverb3
		);

		reverbConfigDirty = false;
	}



	private static int createLowPassFilter() {

		/*
		 * Do not let an earlier unrelated OpenAL error
		 * be attributed to this allocation.
		 */
		AL10.alGetError();

		final int filter = EFX10.alGenFilters();

		if (filter == 0) {

			checkErrorLog(
					"OpenAL failed to allocate an EFX filter."
			);

			throw new IllegalStateException(
					"OpenAL returned filter ID 0."
			);
		}

		if (checkErrorLog(
				"OpenAL error while allocating an EFX filter."
		)) {

			deleteFilterQuietly(filter);

			throw new IllegalStateException(
					"Failed to allocate an EFX filter."
			);
		}

		EFX10.alFilteri(
				filter,
				EFX10.AL_FILTER_TYPE,
				EFX10.AL_FILTER_LOWPASS
		);

		if (checkErrorLog(
				"OpenAL error while configuring an EFX low-pass filter."
		)) {

			deleteFilterQuietly(filter);

			throw new IllegalStateException(
					"Failed to configure an EFX low-pass filter."
			);
		}

		return filter;
	}


	private static void applyMovingSoundPositionOffset(
			int sourceID,
			float posX,
			float posY,
			float posZ
	) {

		if (sourceID <= 0) {
			return;
		}

		final Vec3d offset =
				SOUND_POSITION_OFFSETS.get(sourceID);

		if (offset == null) {
			return;
		}

		final Vec3d originalPosition =
				new Vec3d(posX, posY, posZ);

		setSoundPos(
				sourceID,
				originalPosition.add(offset)
		);
	}


	private static SourceFilters getSourceFilters(
			final int sourceID
	) {

		if (!efxAvailable || sourceID <= 0) {
			return null;
		}

		final ALCcontext context =
				ALC10.alcGetCurrentContext();

		/*
		 * Filter IDs and auxiliary slots belong to a specific
		 * OpenAL context. Never use them in another context.
		 */
		if (context == null ||
				context != efxContext) {
			return null;
		}

		ConcurrentHashMap<Integer, SourceFilters> contextFilters =
				SOURCE_FILTERS.get(context);

		if (contextFilters == null) {

			final ConcurrentHashMap<Integer, SourceFilters> newContextFilters =
					new ConcurrentHashMap<>();

			final ConcurrentHashMap<Integer, SourceFilters> existing =
					SOURCE_FILTERS.putIfAbsent(
							context,
							newContextFilters
					);

			contextFilters = existing != null
					? existing
					: newContextFilters;
		}

		SourceFilters filters =
				contextFilters.get(sourceID);

		if (filters != null) {
			return filters;
		}

		synchronized (contextFilters) {

			filters = contextFilters.get(sourceID);

			if (filters == null) {

				try {

					filters = new SourceFilters();

					contextFilters.put(
							sourceID,
							filters
					);

				} catch (RuntimeException | LinkageError e) {

					logger.error(
							"Failed to create EFX filters for OpenAL source {}",
							sourceID,
							e
					);

					if (contextFilters.isEmpty()) {
						SOURCE_FILTERS.remove(context, contextFilters);
					}

					return null;
				}
			}
		}

		return filters;
	}



	private static void setupEFX() {

		efxAvailable = false;
		maxAuxSends = 0;

		efxContext = null;
		reverbConfigDirty = true;

		final ALCcontext currentContext =
				ALC10.alcGetCurrentContext();

		if (currentContext == null) {

			logError(
					"No current OpenAL context. EFX initialization aborted."
			);

			return;
		}

		final ALCdevice currentDevice =
				ALC10.alcGetContextsDevice(currentContext);

		if (currentDevice == null) {

			logError(
					"No OpenAL device found. EFX initialization aborted."
			);

			return;
		}

		if (!ALC10.alcIsExtensionPresent(
				currentDevice,
				"ALC_EXT_EFX"
		)) {

			logError(
					"EFX Extension not found on current device. Aborting."
			);

			return;
		}

		int slot0 = 0;
		int slot1 = 0;
		int slot2 = 0;
		int slot3 = 0;

		int effect0 = 0;
		int effect1 = 0;
		int effect2 = 0;
		int effect3 = 0;

		try {

			/*
			 * Query the number of auxiliary sends supported
			 * by the current OpenAL device.
			 */
			ALC10.alcGetError(currentDevice);

			final IntBuffer buffer =
					BufferUtils.createIntBuffer(1);

			ALC10.alcGetInteger(
					currentDevice,
					EFX10.ALC_MAX_AUXILIARY_SENDS,
					buffer
			);

			final int alcError =
					ALC10.alcGetError(currentDevice);

			if (alcError != ALC10.ALC_NO_ERROR) {

				throw new IllegalStateException(
						"Failed to query EFX auxiliary sends. ALC error: " +
								alcError
				);
			}

			maxAuxSends = Math.max(0, buffer.get(0));

			log(
					"Max auxiliary sends: " + maxAuxSends
			);

			/*
			 * Allocate all effect slots.
			 *
			 * The existing routing uses up to four auxiliary sends.
			 */
			slot0 = createAuxiliaryEffectSlot();
			slot1 = createAuxiliaryEffectSlot();
			slot2 = createAuxiliaryEffectSlot();
			slot3 = createAuxiliaryEffectSlot();

			/*
			 * Allocate the four reverb effects.
			 */
			effect0 = createReverbEffect();
			effect1 = createReverbEffect();
			effect2 = createReverbEffect();
			effect3 = createReverbEffect();

			/*
			 * Publish the new handles only after every allocation
			 * has completed successfully.
			 */
			auxFXSlot0 = slot0;
			auxFXSlot1 = slot1;
			auxFXSlot2 = slot2;
			auxFXSlot3 = slot3;

			reverb0 = effect0;
			reverb1 = effect1;
			reverb2 = effect2;
			reverb3 = effect3;

			efxContext = currentContext;

			efxAvailable = true;

			log("EFX Extension recognized and initialized.");

			applyConfigChanges();

		} catch (Throwable e) {

			efxContext = null;

			reverbConfigDirty = true;

			/*
			 * Roll back partially created resources.
			 *
			 * Effects must be deleted before their auxiliary slots.
			 */
			deleteReverbEffectQuietly(effect3);
			deleteReverbEffectQuietly(effect2);
			deleteReverbEffectQuietly(effect1);
			deleteReverbEffectQuietly(effect0);

			deleteAuxiliaryEffectSlotQuietly(slot3);
			deleteAuxiliaryEffectSlotQuietly(slot2);
			deleteAuxiliaryEffectSlotQuietly(slot1);
			deleteAuxiliaryEffectSlotQuietly(slot0);

			auxFXSlot0 = 0;
			auxFXSlot1 = 0;
			auxFXSlot2 = 0;
			auxFXSlot3 = 0;

			reverb0 = 0;
			reverb1 = 0;
			reverb2 = 0;
			reverb3 = 0;

			maxAuxSends = 0;
			efxAvailable = false;

			logger.error(
					"Failed to initialize EFX. Sound Physics will continue without EFX.",
					e
			);
		}
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
	public static void setLastSound(
			final ISound snd,
			final SoundCategory sc,
			final ResourceLocation soundRes,
			final ResourceLocation eventRes,
			final String sourceName
	) {
		lastSoundName = eventRes.toString() + "|" + soundRes.getPath();

		SoundCategory actualCategory = getSoundCategory(
				sc,
				lastSoundName
		);

		lastSoundCategory = actualCategory;
		lastSoundAtt = snd.getAttenuationType();

		boolean moving = snd instanceof ITickableSound;

		// Preserve the original behavior of the global volume
		// multiplier and stereo downmix for moving sounds.
		if (moving) {
			lastSoundCategory = SoundCategory.RECORDS;
		}

		if (sourceName == null) {
			return;
		}

		SOUND_SOURCES.put(
				sourceName,
				new SoundSourceData(
						actualCategory,
						lastSoundName,
						snd.getAttenuationType(),
						moving,
						getCurrentWorldTime()
				)
		);
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
	public static void onPlaySound(
			final float posX,
			final float posY,
			final float posZ,
			final int sourceID,
			SoundCategory soundCat,
			String soundName,
			ISound.AttenuationType attType
	) {
		boolean eligibleForRateLimit =
				soundCat != null &&
						soundCat != SoundCategory.MASTER &&
						soundCat != SoundCategory.MUSIC &&
						(soundCat != SoundCategory.RECORDS || Config.updateMovingSounds) &&
						attType != null &&
						attType != ISound.AttenuationType.NONE;

		if (mc != null &&
				mc.player != null &&
				mc.world != null &&
				ClientWorldSnapshotManager.getSnapshot() != null &&
				eligibleForRateLimit &&
				SoundRateManager.shouldSkip(soundName)) {

			setDefaultEnvironment(sourceID);
			return;
		}

		evaluateEnvironment(
				sourceID,
				posX,
				posY,
				posZ,
				soundCat,
				soundName,
				attType
		);
	}

	/**
	 * CALLED BY ASM INJECTED CODE!
	 */
	public static SoundBuffer onLoadSound(
			SoundBuffer buff,
			String filename
	) {

		if (buff == null ||
				buff.audioData == null ||
				buff.audioFormat == null ||
				!Config.autoSteroDownmix ||
				buff.audioFormat.getChannels() != 2) {
			return buff;
		}

		if (mc == null ||
				mc.player == null ||
				mc.world == null ||
				lastSoundCategory == SoundCategory.RECORDS ||
				lastSoundCategory == SoundCategory.MUSIC ||
				filename == null ||
				allPattern.matcher(filename).matches()) {

			if (Config.autoSteroDownmixLogging) {

				log(
						"Not converting sound '" +
								filename +
								"' (" +
								buff.audioFormat +
								")"
				);
			}

			return buff;
		}

		final AudioFormat originalFormat =
				buff.audioFormat;

		final int bits =
				originalFormat.getSampleSizeInBits();

		if (bits != 8 && bits != 16) {
			return buff;
		}

		final int bytesPerSample = bits / 8;

		final int expectedFrameSize =
				bytesPerSample * 2;

		/*
		 * Do not process formats with an unknown or unexpected
		 * frame layout.
		 */
		if (originalFormat.getFrameSize() != expectedFrameSize ||
				buff.audioData.length == 0 ||
				buff.audioData.length % expectedFrameSize != 0) {
			return buff;
		}

		final AudioFormat.Encoding encoding =
				originalFormat.getEncoding();

		final boolean signed =
				AudioFormat.Encoding.PCM_SIGNED.equals(encoding);

		final boolean unsigned =
				AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding);

		/*
		 * Compressed audio and floating-point PCM are not modified.
		 */
		if (!signed && !unsigned) {
			return buff;
		}

		final boolean bigEndian =
				originalFormat.isBigEndian();

		final int frameCount =
				buff.audioData.length / expectedFrameSize;

		final int outputLength =
				frameCount * bytesPerSample;

		final ByteBuffer data =
				ByteBuffer.wrap(buff.audioData);

		data.order(
				bigEndian
						? ByteOrder.BIG_ENDIAN
						: ByteOrder.LITTLE_ENDIAN
		);

		if (bits == 8) {

			/*
			 * Compact stereo 8-bit samples in place.
			 *
			 * Unsigned PCM uses the full 0..255 sample range.
			 * Signed PCM uses the -128..127 range.
			 */
			for (int frame = 0; frame < frameCount; frame++) {

				final int inputOffset =
						frame * expectedFrameSize;

				final int outputOffset = frame;

				final int left = unsigned
						? buff.audioData[inputOffset] & 0xFF
						: buff.audioData[inputOffset];

				final int right = unsigned
						? buff.audioData[inputOffset + 1] & 0xFF
						: buff.audioData[inputOffset + 1];

				final int mixed =
						(left + right) / 2;

				buff.audioData[outputOffset] =
						(byte) mixed;
			}

		} else {

			/*
			 * Compact stereo 16-bit samples in place.
			 *
			 * Unsigned PCM samples are converted to the
			 * 0..65535 range before averaging.
			 */
			for (int frame = 0; frame < frameCount; frame++) {

				final int inputOffset =
						frame * expectedFrameSize;

				final int outputOffset =
						frame * bytesPerSample;

				int left =
						data.getShort(inputOffset);

				int right =
						data.getShort(inputOffset + bytesPerSample);

				if (unsigned) {

					left &= 0xFFFF;
					right &= 0xFFFF;
				}

				final int mixed =
						(left + right) / 2;

				data.putShort(
						outputOffset,
						(short) mixed
				);
			}
		}

		final AudioFormat monoFormat = new AudioFormat(
				encoding,
				originalFormat.getSampleRate(),
				bits,
				1,
				bytesPerSample,
				originalFormat.getFrameRate(),
				bigEndian
		);

		buff.audioFormat = monoFormat;

		buff.trimData(outputLength);

		if (Config.autoSteroDownmixLogging) {

			log(
					"Converted sound '" +
							filename +
							"' from stereo (" +
							originalFormat +
							") to mono (" +
							monoFormat +
							")"
			);
		}

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

	private static long getCurrentWorldTime() {
		if (mc == null || mc.world == null) {
			return Long.MIN_VALUE;
		}

		return mc.world.getTotalWorldTime();
	}

	private static void setDefaultEnvironment(final int sourceID) {

		SOUND_POSITION_OFFSETS.remove(sourceID);

		setEnvironment(
				sourceID,
				0.0f,
				0.0f,
				0.0f,
				0.0f,
				1.0f,
				1.0f,
				1.0f,
				1.0f,
				1.0f,
				1.0f,
				1.0f
		);
	}

	public static void onSoundSourceRemoved(String sourceName) {
		if (sourceName != null) {
			SOUND_SOURCES.remove(sourceName);
		}
	}

	public static void clearSoundSources() {
		SOUND_SOURCES.clear();
		SOUND_POSITION_OFFSETS.clear();
	}

	/**
	 * Processes a sound using metadata belonging to its source.
	 */
	public static void onPlaySound(
			String sourceName,
			float posX,
			float posY,
			float posZ,
			int sourceID
	) {
		SoundSourceData data = SOUND_SOURCES.get(sourceName);

		if (data == null) {
			// Compatibility fallback for external sounds.
			onPlaySound(posX, posY, posZ, sourceID);
			return;
		}

		data.lastEvaluationTick = getCurrentWorldTime();

		if (data.moving && !Config.updateMovingSounds) {
			data.acousticsEnabled = false;
			setDefaultEnvironment(sourceID);
			return;
		}

		onPlaySound(
				posX,
				posY,
				posZ,
				sourceID,
				data.category,
				data.soundName,
				data.attenuationType
		);

		data.acousticsEnabled = data.moving && Config.updateMovingSounds;
	}

	/**
	 * Reevaluates a moving sound source periodically.
	 */

	public static void onMovingSoundPosition(
			String sourceName,
			float posX,
			float posY,
			float posZ,
			int sourceID
	) {

		final SoundSourceData data =
				SOUND_SOURCES.get(sourceName);

		if (data == null || !data.moving) {
			return;
		}

		/*
		 * When periodic processing is disabled,
		 * restore the original source position.
		 */
		if (!Config.updateMovingSounds) {

			SOUND_POSITION_OFFSETS.remove(sourceID);

			if (data.acousticsEnabled) {

				setDefaultEnvironment(sourceID);

				data.acousticsEnabled = false;
			}

			return;
		}

		if (mc == null || mc.world == null) {

			SOUND_POSITION_OFFSETS.remove(sourceID);

			return;
		}

		final long currentTick =
				getCurrentWorldTime();

		if (currentTick == Long.MIN_VALUE) {
			SOUND_POSITION_OFFSETS.remove(sourceID);
			return;
		}

		final int updateInterval = Math.max(
				1,
				Config.soundUpdateInterval
		);

		final long lastTick =
				data.lastEvaluationTick;

		/*
		 * Initialize or recover the timer after a world change.
		 * Preserve the last calculated apparent position.
		 */
		if (lastTick == Long.MIN_VALUE ||
				currentTick < lastTick) {

			data.lastEvaluationTick = currentTick;

			applyMovingSoundPositionOffset(
					sourceID,
					posX,
					posY,
					posZ
			);

			return;
		}

		/*
		 * Do not repeat expensive ray tracing every time
		 * the underlying source position changes.
		 *
		 * Reapply the previous calculated offset instead.
		 */
		if (currentTick - lastTick < updateInterval) {

			applyMovingSoundPositionOffset(
					sourceID,
					posX,
					posY,
					posZ
			);

			return;
		}

		/*
		 * Update before evaluation to prevent repeated processing
		 * if multiple position commands arrive during the same tick.
		 */
		data.lastEvaluationTick = currentTick;

		onPlaySound(
				posX,
				posY,
				posZ,
				sourceID,
				data.category,
				data.soundName,
				data.attenuationType
		);

		data.acousticsEnabled = true;
	}




	private static boolean isPlayerInsideOfMaterial(final Material material, final ClientWorldSnapshot worldSnapshot) {
		if (mc.player.getRidingEntity() instanceof EntityBoat) {
			return false;
		}
		final double eyeY = mc.player.posY + getPlayerEyeHeight();
		final BlockPos eyePos = new BlockPos(mc.player.posX, eyeY, mc.player.posZ);
		return worldSnapshot.getBlockState(eyePos).getMaterial() == material;
	}

	private static boolean isListenerOrSourceUnderwater(
			final double sourceX,
			final double sourceY,
			final double sourceZ,
			final ClientWorldSnapshot worldSnapshot
	) {
		if (isPlayerInsideOfMaterial(Material.WATER, worldSnapshot)) {
			return true;
		}

		final BlockPos sourceBlockPos =
				new BlockPos(sourceX, sourceY, sourceZ);

		return worldSnapshot.getBlockState(sourceBlockPos).getMaterial()
				== Material.WATER;
	}

	@SuppressWarnings("deprecation")
	private static float getBlockReflectivity(
			final BlockPos blockPos,
			final ClientWorldSnapshot worldSnapshot
	) {
		final Block block =
				worldSnapshot.getBlockState(blockPos).getBlock();

		final Float override =
				BlockAcousticManager.getReflectivity(block);

		if (override != null) {
			return override * Config.globalBlockReflectance;
		}

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

		return reflectivity * Config.globalBlockReflectance;
	}

	private static Vec3d getNormalFromFacing(final EnumFacing sideHit) {
		return new Vec3d(sideHit.getDirectionVec());
	}

	/**
	 * Checks whether a position shares unobstructed airspace
	 * with the listener.
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
				SoundDirectionDebug.COLOR_VALID,
				null
		);
	}

	/**
	 * Checks shared airspace and optionally records
	 * the tested path for direction debugging.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			Vec3d soundPosition,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot,
			SoundDirectionDebug.Builder debug,
			int clearColor
	) {
		return getSharedAirspace(
				soundPosition,
				listenerPosition,
				worldSnapshot,
				debug,
				clearColor,
				null
		);
	}

	/**
	 * Checks shared airspace while optionally ignoring
	 * the block from which the ray originated.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			Vec3d soundPosition,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot,
			SoundDirectionDebug.Builder debug,
			int clearColor,
			@Nullable BlockPos ignoredBlock
	) {
		if (worldSnapshot == null) {
			return null;
		}

		if (!worldSnapshot.hasChunkCoverage(
				soundPosition,
				listenerPosition
		)) {

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
				true,
				ignoredBlock
		);

		if (hit == null) {

			if (debug != null) {
				debug.addLine(
						soundPosition,
						listenerPosition,
						clearColor
				);
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
	 * Checks shared airspace starting from a reflection point.
	 * <p>
	 * The block producing the reflection is explicitly ignored.
	 * No artificial positional offset is required.
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
	 * Checks shared airspace starting from a reflection point
	 * and optionally records the tested path.
	 */
	@Nullable
	private static Vec3d getSharedAirspace(
			RayTraceResult hit,
			Vec3d listenerPosition,
			ClientWorldSnapshot worldSnapshot,
			SoundDirectionDebug.Builder debug
	) {
		if (hit == null ||
				hit.typeOfHit != RayTraceResult.Type.BLOCK ||
				hit.sideHit == null ||
				hit.hitVec == null) {
			return null;
		}

		return getSharedAirspace(
				hit.hitVec,
				listenerPosition,
				worldSnapshot,
				debug,
				SoundDirectionDebug.COLOR_VALID,
				hit.getBlockPos()
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
	private static float calculateOcclusion(
			final Vec3d soundPos,
			final Vec3d playerPos,
			final SoundCategory category,
			final String name,
			final ClientWorldSnapshot worldSnapshot
	) {

		if (Config.strictOcclusion) {

			final float result =
					runOcclusion(soundPos, playerPos, worldSnapshot);

			return result >= 0.0f
					? result
					: 0.0f;
		}

		float variationFactor = Config.occlusionVariation;

		final boolean isBlockSound =
				category == SoundCategory.BLOCKS ||
						blockPattern.matcher(name).matches();

		if (isBlockSound) {
			variationFactor = Math.max(variationFactor, 0.49f);
		}

		float minOcclusion = Float.POSITIVE_INFINITY;

		/*
		 * Evaluate the original path.
		 */
		final float directOcclusion =
				runOcclusion(soundPos, playerPos, worldSnapshot);

		if (directOcclusion >= 0.0f) {
			minOcclusion = directOcclusion;
		}

		/*
		 * Evaluate parallel paths to account for small openings.
		 */
		if (variationFactor > 0.0f) {

			for (int x = -1; x <= 1; x += 2) {

				for (int y = -1; y <= 1; y += 2) {

					for (int z = -1; z <= 1; z += 2) {

						final Vec3d offset = new Vec3d(
								x * variationFactor,
								y * variationFactor,
								z * variationFactor
						);

						final float occlusion =
								runOcclusion(
										soundPos.add(offset),
										playerPos.add(offset),
										worldSnapshot
								);

						/*
						 * Ignore incomplete paths.
						 * Only fully evaluated paths participate
						 * in the minimum occlusion calculation.
						 */
						if (occlusion >= 0.0f) {

							minOcclusion = Math.min(
									minOcclusion,
									occlusion
							);
						}
					}
				}
			}
		}

		/*
		 * If no path could be completely evaluated,
		 * do not artificially mute the original sound.
		 */
		return Float.isInfinite(minOcclusion)
				? 0.0f
				: minOcclusion;
	}

	/**
	 * Returns the occlusion contribution of a block.
	 * Door-like blocks are handled according to their open state:
	 * closed doors obstruct sound, while open doors and gates contribute
	 * no additional occlusion.
	 */
	@SuppressWarnings("deprecation")
	private static float getBlockOcclusion(
			final BlockPos blockPos,
			final EnumFacing sideHit,
			final ClientWorldSnapshot worldSnapshot
	) {
		final IBlockState blockState =
				worldSnapshot.getBlockState(blockPos);

		final Block block = blockState.getBlock();

		final Float override =
				BlockAcousticManager.getOcclusion(block);

		if (override != null) {
			return override;
		}

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
			} else if (soundType == SoundType.PLANT ||
					soundType == SoundType.LADDER) {
				blockOcclusion = 0.0f;
			} else {
				blockOcclusion = Config.defaultBlockOcclusionFactor;
			}
		}

		if (!block.isSideSolid(
				blockState,
				worldSnapshot,
				blockPos,
				sideHit
		)) {
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

		// Occlusion disabled.
		if (Config.maxOcclusion <= 0.0f) {
			return 0.0f;
		}

		/*
		 * The path is outside the available snapshot.
		 * Do not interpret missing chunks as solid blocks
		 * or open air.
		 */
		if (!worldSnapshot.hasChunkCoverage(
				soundPos,
				playerPos
		)) {
			return UNKNOWN_OCCLUSION;
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

				/*
				 * The complete path is covered, so null means
				 * that no further block was encountered.
				 */
				SoundDirectionDebug.addOcclusionRay(
						mc.world,
						rayOrigin,
						playerPos,
						SoundDirectionDebug.COLOR_VALID
				);

				break;
			}

			SoundDirectionDebug.addOcclusionRay(
					mc.world,
					rayOrigin,
					rayHit.hitVec,
					SoundDirectionDebug.COLOR_BLOCKED
			);

			final BlockPos blockPos =
					rayHit.getBlockPos();

			occlusionAccumulation += getBlockOcclusion(
					blockPos,
					rayHit.sideHit,
					worldSnapshot
			);

			if (occlusionAccumulation >= Config.maxOcclusion) {
				return Config.maxOcclusion;
			}

			rayOrigin = new Vec3d(
					rayHit.hitVec.x + normalToPlayer.x * 0.1D,
					rayHit.hitVec.y + normalToPlayer.y * 0.1D,
					rayHit.hitVec.z + normalToPlayer.z * 0.1D
			);
		}

		return Math.min(
				occlusionAccumulation,
				Config.maxOcclusion
		);
	}


	/**
	 * Filters reflections that have not travelled far enough.
	 * <p>
	 * A value of 0 disables this restriction.
	 */
	private static float getReflectionDistanceMultiplier(
			final float totalRayDistance
	) {
		final float minimumDistance = Config.reverbAttenuationDistance;

		if (minimumDistance <= 0.0f) {
			return 1.0f;
		}

		return totalRayDistance >= minimumDistance
				? 1.0f
				: 0.0f;
	}

	/**
	 * Calculates distance-based attenuation for reverb sends.
	 * <p>
	 * A value of 0 disables distance attenuation.
	 */
	private static float getReverbDistanceMultiplier(
			final Vec3d soundPos,
			final Vec3d playerPos
	) {
		final float maximumDistance = Config.reverbDistance;

		if (maximumDistance <= 0.0f) {
			return 1.0f;
		}

		final double soundDistance = soundPos.distanceTo(playerPos);

		return MathHelper.clamp(
				1.0f - (float) (soundDistance / maximumDistance),
				0.0f,
				1.0f
		);
	}

	private static void evaluateEnvironment(
			final int sourceID,
			final float posX,
			final float posY,
			final float posZ,
			final SoundCategory category,
			final String name,
			ISound.AttenuationType attType
	) {
		try {
			final ClientWorldSnapshot worldSnapshot =
					ClientWorldSnapshotManager.getSnapshot();

			if (mc.player == null ||
					mc.world == null ||
					worldSnapshot == null ||
					category == SoundCategory.MASTER ||
					attType == ISound.AttenuationType.NONE ||
					(category == SoundCategory.RECORDS && !Config.updateMovingSounds) ||
					category == SoundCategory.MUSIC) {

				setEnvironment(
						sourceID,
						0.0f, 0.0f, 0.0f, 0.0f,
						1.0f, 1.0f, 1.0f, 1.0f,
						1.0f, 1.0f, 1.0f
				);

				return;
			}

			final boolean isRain = rainPattern.matcher(name).matches();

			if (Config.skipRainOcclusionTracing && isRain) {

				setEnvironment(
						sourceID,
						0.0f, 0.0f, 0.0f, 0.0f,
						1.0f, 1.0f, 1.0f, 1.0f,
						1.0f, 1.0f, 1.0f
				);

				return;
			}

			float directCutoff = 1.0f;

			final float absorptionCoeff =
					Config.globalBlockAbsorption * 3.0f;

			final Vec3d playerPos = new Vec3d(
					mc.player.posX,
					mc.player.posY + getPlayerEyeHeight(),
					mc.player.posZ
			);

			final Vec3d soundPos = offsetSoundByName(
					posX,
					posY,
					posZ,
					playerPos,
					name,
					category,
					worldSnapshot
			);

			final SoundDirectionDebug.Builder directionDebug =
					SoundDirectionDebug.begin(
							mc.world,
							soundPos,
							playerPos,
							name
					);

			float airAbsorptionFactor = 1.0f;

			if (Config.snowAirAbsorptionFactor > 1.0f &&
					worldSnapshot.isRaining()) {

				final Vec3d middlePos =
						playerPos.add(soundPos).scale(0.5);

				final BlockPos playerPosBlock = new BlockPos(playerPos);
				final BlockPos soundPosBlock = new BlockPos(soundPos);
				final BlockPos middlePosBlock = new BlockPos(middlePos);

				final int snowingPlayer =
						isSnowingAt(playerPosBlock, false, worldSnapshot) ? 1 : 0;

				final int snowingSound =
						isSnowingAt(soundPosBlock, false, worldSnapshot) ? 1 : 0;

				final int snowingMiddle =
						isSnowingAt(middlePosBlock, false, worldSnapshot) ? 1 : 0;

				final float snowFactor =
						snowingPlayer * 0.25f +
								snowingMiddle * 0.5f +
								snowingSound * 0.25f;

				airAbsorptionFactor = Math.max(
						Config.snowAirAbsorptionFactor *
								worldSnapshot.getRainStrength() *
								snowFactor,
						airAbsorptionFactor
				);
			}

			final float occlusionAccumulation = calculateOcclusion(
					soundPos,
					playerPos,
					category,
					name,
					worldSnapshot
			);

			directCutoff = (float) Math.exp(
					-occlusionAccumulation * absorptionCoeff
			);

			float directGain = (float) Math.pow(directCutoff, 0.1);

			float sendGain0 = 0.0f;
			float sendGain1 = 0.0f;
			float sendGain2 = 0.0f;
			float sendGain3 = 0.0f;

			float sendCutoff0 = 1.0f;
			float sendCutoff1 = 1.0f;
			float sendCutoff2 = 1.0f;
			float sendCutoff3 = 1.0f;

			if (isListenerOrSourceUnderwater(
					posX,
					posY,
					posZ,
					worldSnapshot
			)) {
				directCutoff *= 1.0f - Config.underwaterFilter;
			}

			if (isRain) {

				setEnvironment(
						sourceID,
						sendGain0,
						sendGain1,
						sendGain2,
						sendGain3,
						sendCutoff0,
						sendCutoff1,
						sendCutoff2,
						sendCutoff3,
						directCutoff,
						directGain,
						airAbsorptionFactor
				);

				return;
			}

			final float phi = 1.618033988f;
			final float gAngle = phi * (float) Math.PI * 2.0f;

			final float maxDistance = Config.maxDistance;

			final int numRays = Config.environmentEvaluationRays;
			final int rayBounces = 4;

			final float[] bounceReflectivityRatio =
					new float[rayBounces];

			float sharedAirspace = 0.0f;

			final ReflectedAudio reflectedAudio =
					new ReflectedAudio(
							occlusionAccumulation,
							SoundDirectionExclusions.isExcluded(name),
							SoundDirectionPositionBias.getBias(name)
					);

			if (reflectedAudio.shouldEvaluateDirection()) {

				final Vec3d directSharedAirspaceVector =
						getSharedAirspace(
								soundPos,
								playerPos,
								worldSnapshot,
								directionDebug,
								SoundDirectionDebug.COLOR_DIRECT_CLEAR
						);

				if (directSharedAirspaceVector != null) {
					reflectedAudio.addDirectAirspace(
							directSharedAirspaceVector
					);
				}

			} else if (Config.debugSoundDirection) {

				getSharedAirspace(
						soundPos,
						playerPos,
						worldSnapshot,
						directionDebug,
						SoundDirectionDebug.COLOR_DIRECT_CLEAR
				);
			}

			final float rcpTotalRays =
					1.0f / (numRays * rayBounces);

			final float rcpPrimaryRays =
					1.0f / numRays;

			for (int i = 0; i < numRays; i++) {

				final float fiN = (float) i / numRays;

				final float longitude = gAngle * (float) i;

				final float latitude =
						(float) Math.asin(fiN * 2.0f - 1.0f);

				final Vec3d rayDir = new Vec3d(
						Math.cos(latitude) * Math.cos(longitude),
						Math.cos(latitude) * Math.sin(longitude),
						Math.sin(latitude)
				);

				final Vec3d rayStart = new Vec3d(
						soundPos.x,
						soundPos.y,
						soundPos.z
				);

				final Vec3d rayEnd = new Vec3d(
						rayStart.x + rayDir.x * maxDistance,
						rayStart.y + rayDir.y * maxDistance,
						rayStart.z + rayDir.z * maxDistance
				);

				final boolean rayCoverage =
						Config.debugSoundDirection &&
								worldSnapshot.hasChunkCoverage(
										rayStart,
										rayEnd
								);

				final RayTraceResult rayHit =
						worldSnapshot.rayTraceBlocks(
								rayStart,
								rayEnd,
								true
						);

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

				if (rayHit == null) {
					continue;
				}

				final double rayLength =
						soundPos.distanceTo(rayHit.hitVec);

				BlockPos lastHitBlock = rayHit.getBlockPos();

				Vec3d lastHitPos = rayHit.hitVec;

				Vec3d lastHitNormal =
						getNormalFromFacing(rayHit.sideHit);

				Vec3d lastRayDir = rayDir;

				float totalRayDistance = (float) rayLength;

				/*
				 * Check the first reflection point.
				 *
				 * getSharedAirspace now ignores the block
				 * that generated this reflection.
				 */
				final Vec3d firstSharedAirspaceVector =
						getSharedAirspace(
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

				for (int j = 0; j < rayBounces; j++) {

					final Vec3d newRayDir =
							reflect(lastRayDir, lastHitNormal);

					/*
					 * Start directly at the reflection point.
					 *
					 * The previous block is passed separately
					 * and explicitly excluded from ray tracing.
					 */
					final Vec3d newRayStart = lastHitPos;

					final Vec3d newRayEnd = new Vec3d(
							newRayStart.x + newRayDir.x * maxDistance,
							newRayStart.y + newRayDir.y * maxDistance,
							newRayStart.z + newRayDir.z * maxDistance
					);

					final boolean bounceCoverage =
							Config.debugSoundDirection &&
									worldSnapshot.hasChunkCoverage(
											newRayStart,
											newRayEnd
									);

					final RayTraceResult newRayHit =
							worldSnapshot.rayTraceBlocks(
									newRayStart,
									newRayEnd,
									true,
									lastHitBlock
							);

					directionDebug.addLine(
							newRayStart,
							newRayHit == null
									? newRayEnd
									: newRayHit.hitVec,
							newRayHit != null || bounceCoverage
									? SoundDirectionDebug.COLOR_BOUNCE
									: SoundDirectionDebug.COLOR_UNKNOWN
					);

					if (newRayHit == null &&
							!worldSnapshot.hasChunkCoverage(
									newRayStart,
									newRayEnd
							)) {
						break;
					}

					float energyTowardsPlayer = 0.25f;

					final float blockReflectivity =
							getBlockReflectivity(lastHitBlock, worldSnapshot);

					energyTowardsPlayer *=
							blockReflectivity * 0.75f + 0.25f;

					if (newRayHit == null) {

						totalRayDistance +=
								(float) lastHitPos.distanceTo(playerPos);

					} else {

						final double newRayLength =
								lastHitPos.distanceTo(newRayHit.hitVec);

						bounceReflectivityRatio[j] += blockReflectivity;

						totalRayDistance += (float) newRayLength;

						lastHitPos = newRayHit.hitVec;

						lastHitNormal =
								getNormalFromFacing(newRayHit.sideHit);

						lastRayDir = newRayDir;

						lastHitBlock = newRayHit.getBlockPos();

						if (!Config.simplerSharedAirspaceSimulation ||
								j == rayBounces - 1) {

							/*
							 * No normal offset.
							 *
							 * Explicitly ignore the block that produced
							 * the current reflection.
							 */
							final Vec3d finalRayStart = lastHitPos;

							final Vec3d sharedAirspaceVector =
									getSharedAirspace(
											finalRayStart,
											playerPos,
											worldSnapshot,
											directionDebug,
											SoundDirectionDebug.COLOR_VALID,
											lastHitBlock
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

					energyTowardsPlayer *=
							getReflectionDistanceMultiplier(totalRayDistance);

					final float reflectionDelay =
							(float) Math.max(totalRayDistance, 0.0)
									* 0.12f
									* blockReflectivity;

					final float cross0 =
							1.0f - MathHelper.clamp(
									Math.abs(reflectionDelay),
									0.0f,
									1.0f
							);

					final float cross1 =
							1.0f - MathHelper.clamp(
									Math.abs(reflectionDelay - 1.0f),
									0.0f,
									1.0f
							);

					final float cross2 =
							1.0f - MathHelper.clamp(
									Math.abs(reflectionDelay - 2.0f),
									0.0f,
									1.0f
							);

					final float cross3 =
							MathHelper.clamp(
									reflectionDelay - 2.0f,
									0.0f,
									1.0f
							);

					sendGain0 +=
							cross0 * energyTowardsPlayer * 6.4f * rcpTotalRays;

					sendGain1 +=
							cross1 * energyTowardsPlayer * 12.8f * rcpTotalRays;

					sendGain2 +=
							cross2 * energyTowardsPlayer * 12.8f * rcpTotalRays;

					sendGain3 +=
							cross3 * energyTowardsPlayer * 12.8f * rcpTotalRays;

					if (newRayHit == null) {
						break;
					}
				}
			}


			final Vec3d redirectedSoundPos =
					reflectedAudio.evaluateSoundPosition(
							soundPos,
							playerPos
					);

			if (redirectedSoundPos != null) {

				setSoundPos(
						sourceID,
						redirectedSoundPos
				);

				/*
				 * Store the apparent position relative to the original
				 * source coordinates.
				 *
				 * Moving sounds can reuse this offset between
				 * full acoustic evaluations.
				 */
				final Vec3d originalPosition =
						new Vec3d(posX, posY, posZ);

				SOUND_POSITION_OFFSETS.put(
						sourceID,
						redirectedSoundPos.subtract(originalPosition)
				);

			} else {

				SOUND_POSITION_OFFSETS.remove(sourceID);
			}

			directionDebug.finish(
					redirectedSoundPos,
					reflectedAudio,
					sourceID,
					occlusionAccumulation
			);


			bounceReflectivityRatio[0] /= numRays;
			bounceReflectivityRatio[1] /= numRays;
			bounceReflectivityRatio[2] /= numRays;
			bounceReflectivityRatio[3] /= numRays;

			sharedAirspace *= 64.0f;

			if (Config.simplerSharedAirspaceSimulation) {
				sharedAirspace *= rcpPrimaryRays;
			} else {
				sharedAirspace *= rcpTotalRays;
			}

			final float sharedAirspaceWeight0 =
					MathHelper.clamp(sharedAirspace / 20.0f, 0.0f, 1.0f);

			final float sharedAirspaceWeight1 =
					MathHelper.clamp(sharedAirspace / 15.0f, 0.0f, 1.0f);

			final float sharedAirspaceWeight2 =
					MathHelper.clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);

			final float sharedAirspaceWeight3 =
					MathHelper.clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);

			sendCutoff0 =
					(float) Math.exp(-occlusionAccumulation * absorptionCoeff)
							* (1.0f - sharedAirspaceWeight0)
							+ sharedAirspaceWeight0;

			sendCutoff1 =
					(float) Math.exp(-occlusionAccumulation * absorptionCoeff)
							* (1.0f - sharedAirspaceWeight1)
							+ sharedAirspaceWeight1;

			sendCutoff2 =
					(float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f)
							* (1.0f - sharedAirspaceWeight2)
							+ sharedAirspaceWeight2;

			sendCutoff3 =
					(float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.5f)
							* (1.0f - sharedAirspaceWeight3)
							+ sharedAirspaceWeight3;

			final float averageSharedAirspace =
					(sharedAirspaceWeight0 +
							sharedAirspaceWeight1 +
							sharedAirspaceWeight2 +
							sharedAirspaceWeight3) * 0.25f;

			directCutoff = Math.max(
					(float) Math.pow(averageSharedAirspace, 0.5) * 0.2f,
					directCutoff
			);

			directGain = (float) Math.pow(directCutoff, 0.1);

			sendGain1 *= bounceReflectivityRatio[1];

			sendGain2 *=
					(float) Math.pow(bounceReflectivityRatio[2], 3.0);

			sendGain3 *=
					(float) Math.pow(bounceReflectivityRatio[3], 4.0);

			// Clamp reverb gains.
			sendGain0 = MathHelper.clamp(sendGain0, 0.0f, 1.0f);
			sendGain1 = MathHelper.clamp(sendGain1, 0.0f, 1.0f);

			sendGain2 = MathHelper.clamp(
					sendGain2 * 1.05f - 0.05f,
					0.0f,
					1.0f
			);

			sendGain3 = MathHelper.clamp(
					sendGain3 * 1.05f - 0.05f,
					0.0f,
					1.0f
			);

			// Apply frequency-dependent attenuation.
			sendGain0 *= (float) Math.pow(sendCutoff0, 0.1);
			sendGain1 *= (float) Math.pow(sendCutoff1, 0.1);
			sendGain2 *= (float) Math.pow(sendCutoff2, 0.1);
			sendGain3 *= (float) Math.pow(sendCutoff3, 0.1);

			// Distance-based reverb attenuation and global echo volume.
			final float reverbDistanceMultiplier =
					getReverbDistanceMultiplier(soundPos, playerPos);

			final float echoMultiplier =
					Math.max(0.0f, Config.globalEchoMultiplier);

			final float finalReverbMultiplier =
					reverbDistanceMultiplier * echoMultiplier;

			sendGain0 = MathHelper.clamp(
					sendGain0 * finalReverbMultiplier,
					0.0f,
					1.0f
			);

			sendGain1 = MathHelper.clamp(
					sendGain1 * finalReverbMultiplier,
					0.0f,
					1.0f
			);

			sendGain2 = MathHelper.clamp(
					sendGain2 * finalReverbMultiplier,
					0.0f,
					1.0f
			);

			sendGain3 = MathHelper.clamp(
					sendGain3 * finalReverbMultiplier,
					0.0f,
					1.0f
			);

			if (isListenerOrSourceUnderwater(
					posX,
					posY,
					posZ,
					worldSnapshot
			)) {
				sendCutoff0 *= 0.4f;
				sendCutoff1 *= 0.4f;
				sendCutoff2 *= 0.4f;
				sendCutoff3 *= 0.4f;
			}

			setEnvironment(
					sourceID,
					sendGain0,
					sendGain1,
					sendGain2,
					sendGain3,
					sendCutoff0,
					sendCutoff1,
					sendCutoff2,
					sendCutoff3,
					directCutoff,
					directGain,
					airAbsorptionFactor
			);

		} catch (Exception e) {

			SOUND_POSITION_OFFSETS.remove(sourceID);

			logger.error("Error while evaluation environment:", e);

			setEnvironment(
					sourceID,
					0.0f, 0.0f, 0.0f, 0.0f,
					1.0f, 1.0f, 1.0f, 1.0f,
					1.0f, 1.0f, 1.0f
			);
		}
	}


	private static void setEnvironment(
			final int sourceID,
			final float sendGain0,
			final float sendGain1,
			final float sendGain2,
			final float sendGain3,
			final float sendCutoff0,
			final float sendCutoff1,
			final float sendCutoff2,
			final float sendCutoff3,
			final float directCutoff,
			final float directGain,
			final float airAbsorptionFactor
	) {

		try {

			applyReverbConfigIfPossible();

			final SourceFilters filters =
					getSourceFilters(sourceID);

			if (filters == null) {
				return;
			}

			/*
			 * Reverb send 0.
			 */
			if (maxAuxSends >= 4) {

				EFX10.alFilterf(
						filters.sendFilter0,
						EFX10.AL_LOWPASS_GAIN,
						sendGain0
				);

				EFX10.alFilterf(
						filters.sendFilter0,
						EFX10.AL_LOWPASS_GAINHF,
						sendCutoff0
				);

				AL11.alSource3i(
						sourceID,
						EFX10.AL_AUXILIARY_SEND_FILTER,
						auxFXSlot0,
						3,
						filters.sendFilter0
				);
			}

			/*
			 * Reverb send 1.
			 */
			if (maxAuxSends >= 3) {

				EFX10.alFilterf(
						filters.sendFilter1,
						EFX10.AL_LOWPASS_GAIN,
						sendGain1
				);

				EFX10.alFilterf(
						filters.sendFilter1,
						EFX10.AL_LOWPASS_GAINHF,
						sendCutoff1
				);

				AL11.alSource3i(
						sourceID,
						EFX10.AL_AUXILIARY_SEND_FILTER,
						auxFXSlot1,
						2,
						filters.sendFilter1
				);
			}

			/*
			 * Reverb send 2.
			 */
			if (maxAuxSends >= 2) {

				EFX10.alFilterf(
						filters.sendFilter2,
						EFX10.AL_LOWPASS_GAIN,
						sendGain2
				);

				EFX10.alFilterf(
						filters.sendFilter2,
						EFX10.AL_LOWPASS_GAINHF,
						sendCutoff2
				);

				AL11.alSource3i(
						sourceID,
						EFX10.AL_AUXILIARY_SEND_FILTER,
						auxFXSlot2,
						1,
						filters.sendFilter2
				);
			}

			/*
			 * Reverb send 3.
			 */
			if (maxAuxSends >= 1) {

				EFX10.alFilterf(
						filters.sendFilter3,
						EFX10.AL_LOWPASS_GAIN,
						sendGain3
				);

				EFX10.alFilterf(
						filters.sendFilter3,
						EFX10.AL_LOWPASS_GAINHF,
						sendCutoff3
				);

				AL11.alSource3i(
						sourceID,
						EFX10.AL_AUXILIARY_SEND_FILTER,
						auxFXSlot3,
						0,
						filters.sendFilter3
				);
			}

			/*
			 * Direct sound filter.
			 */
			EFX10.alFilterf(
					filters.directFilter,
					EFX10.AL_LOWPASS_GAIN,
					directGain
			);

			EFX10.alFilterf(
					filters.directFilter,
					EFX10.AL_LOWPASS_GAINHF,
					directCutoff
			);

			AL10.alSourcei(
					sourceID,
					EFX10.AL_DIRECT_FILTER,
					filters.directFilter
			);

			/*
			 * Air absorption.
			 */
			AL10.alSourcef(
					sourceID,
					EFX10.AL_AIR_ABSORPTION_FACTOR,
					MathHelper.clamp(
							Config.airAbsorption * airAbsorptionFactor,
							0.0f,
							10.0f
					)
			);

			checkErrorLog(
					"Error while setting environment for source: " +
							sourceID
			);

		} catch (RuntimeException | LinkageError e) {

			logger.error(
					"Failed to apply EFX environment to OpenAL source {}",
					sourceID,
					e
			);
		}
	}



	private static int createAuxiliaryEffectSlot() {

		AL10.alGetError();

		final int slot =
				EFX10.alGenAuxiliaryEffectSlots();

		final boolean generationFailed =
				checkErrorLog(
						"Failed to create an EFX auxiliary effect slot."
				);

		if (slot == 0 || generationFailed) {

			deleteAuxiliaryEffectSlotQuietly(slot);

			throw new IllegalStateException(
					"Could not create an EFX auxiliary effect slot."
			);
		}

		EFX10.alAuxiliaryEffectSloti(
				slot,
				EFX10.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO,
				AL10.AL_TRUE
		);

		if (checkErrorLog(
				"Failed to configure an EFX auxiliary effect slot."
		)) {

			deleteAuxiliaryEffectSlotQuietly(slot);

			throw new IllegalStateException(
					"Could not configure an EFX auxiliary effect slot."
			);
		}

		return slot;
	}

	private static int createReverbEffect() {

		AL10.alGetError();

		final int effect = EFX10.alGenEffects();

		final boolean generationFailed =
				checkErrorLog(
						"Failed to create an EFX reverb effect."
				);

		if (effect == 0 || generationFailed) {

			deleteReverbEffectQuietly(effect);

			throw new IllegalStateException(
					"Could not create an EFX reverb effect."
			);
		}

		EFX10.alEffecti(
				effect,
				EFX10.AL_EFFECT_TYPE,
				EFX10.AL_EFFECT_EAXREVERB
		);

		if (checkErrorLog(
				"Failed to configure an EFX reverb effect."
		)) {

			deleteReverbEffectQuietly(effect);

			throw new IllegalStateException(
					"Could not configure an EFX reverb effect."
			);
		}

		return effect;
	}

	private static void deleteAuxiliaryEffectSlotQuietly(int slot) {

		if (slot <= 0) {
			return;
		}

		try {

			EFX10.alDeleteAuxiliaryEffectSlots(slot);

			AL10.alGetError();

		} catch (Throwable ignored) {
		}
	}

	private static void deleteReverbEffectQuietly(int effect) {

		if (effect <= 0) {
			return;
		}

		try {

			EFX10.alDeleteEffects(effect);

			AL10.alGetError();

		} catch (Throwable ignored) {
		}
	}



	/**
	 * Releases EFX filters belonging to a deleted OpenAL source.
	 * <p>
	 * This is called after Paulscode deletes the physical source,
	 * so its filter attachments no longer exist.
	 */

	public static void onOpenALSourceDestroyed(int sourceID) {

		if (sourceID <= 0) {
			return;
		}

		SOUND_POSITION_OFFSETS.remove(sourceID);

		final ALCcontext context =
				ALC10.alcGetCurrentContext();

		if (context == null) {
			return;
		}

		final ConcurrentHashMap<Integer, SourceFilters> contextFilters =
				SOURCE_FILTERS.get(context);

		if (contextFilters == null) {
			return;
		}

		final SourceFilters filters =
				contextFilters.remove(sourceID);

		if (filters == null) {
			return;
		}

		try {

			final IntBuffer filterIds =
					BufferUtils.createIntBuffer(5);

			if (filters.directFilter > 0) {
				filterIds.put(filters.directFilter);
			}

			if (filters.sendFilter0 > 0) {
				filterIds.put(filters.sendFilter0);
			}

			if (filters.sendFilter1 > 0) {
				filterIds.put(filters.sendFilter1);
			}

			if (filters.sendFilter2 > 0) {
				filterIds.put(filters.sendFilter2);
			}

			if (filters.sendFilter3 > 0) {
				filterIds.put(filters.sendFilter3);
			}

			filterIds.flip();

			if (filterIds.hasRemaining()) {

				EFX10.alDeleteFilters(filterIds);

				checkErrorLog(
						"Failed to delete EFX filters for OpenAL source " +
								sourceID
				);
			}

		} catch (RuntimeException | LinkageError e) {

			logger.warn(
					"Failed to release EFX filters for OpenAL source {}",
					sourceID,
					e
			);
		}

		if (contextFilters.isEmpty()) {
			SOURCE_FILTERS.remove(context, contextFilters);
		}
	}


	/**
	 * Releases Java references to EFX objects before the OpenAL
	 * context is destroyed.
	 * <p>
	 * Native EFX objects are owned by the OpenAL context and are
	 * destroyed automatically with it.
	 */
	public static void onOpenALContextDestroy() {

		efxContext = null;

		reverbConfigDirty = true;

		SOURCE_FILTERS.clear();
		SOUND_POSITION_OFFSETS.clear();
		SOUND_SOURCES.clear();

		efxAvailable = false;

		maxAuxSends = 0;

		auxFXSlot0 = 0;
		auxFXSlot1 = 0;
		auxFXSlot2 = 0;
		auxFXSlot3 = 0;

		reverb0 = 0;
		reverb1 = 0;
		reverb2 = 0;
		reverb3 = 0;

		lastSoundCategory = null;
		lastSoundName = null;
		lastSoundAtt = null;
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

	private static void deleteFilterQuietly(int filter) {

		if (filter <= 0) {
			return;
		}

		try {

			EFX10.alDeleteFilters(filter);

			AL10.alGetError();

		} catch (Throwable e) {

			logger.warn(
					"Could not delete EFX filter {}",
					filter,
					e
			);
		}
	}


}
