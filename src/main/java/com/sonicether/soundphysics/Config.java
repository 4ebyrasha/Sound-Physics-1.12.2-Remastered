package com.sonicether.soundphysics;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Config {

	public static final Config instance;
	private Configuration forgeConfig;

	// general
	public static float reverbAttenuationDistance;
	public static float reverbDistance;
	public static boolean soundDirectionEvaluation;
	public static String[] soundDirectionPositionBiasOverrides;
	public static boolean redirectNonOccludedSounds;
	public static float rolloffFactor;
	public static float globalReverbGain;
	public static float globalVolumeMultiplier;
	public static float globalReverbBrightness;
	public static float soundDistanceAllowance;
	public static float globalBlockAbsorption;
	public static float defaultBlockOcclusionFactor;
	public static float globalBlockReflectance;
	public static float airAbsorption;
	public static float snowAirAbsorptionFactor;
	public static float underwaterFilter;
	public static boolean noteBlockEnable;
	public static float maxDistance;
	public static boolean volumeMulOnlyAffected;
	public static float globalEchoMultiplier;

	// performance
	public static boolean updateMovingSounds;
	public static int soundUpdateInterval;
	public static boolean skipRainOcclusionTracing;
	public static int environmentEvaluationRays;
	public static boolean simplerSharedAirspaceSimulation;
	public static boolean strictOcclusion;
	public static float occlusionVariation;
	public static float nonFullBlockOcclusionFactor;
	public static int maxOcclusionRays;
	public static float maxOcclusion;
	public static String[] soundRateLimits;

	// block properties
	public static String[] blockAcousticOverrides;
	public static float stoneReflectivity;
	public static float woodReflectivity;
	public static float groundReflectivity;
	public static float plantReflectivity;
	public static float metalReflectivity;
	public static float glassReflectivity;
	public static float clothReflectivity;
	public static float sandReflectivity;
	public static float snowReflectivity;

	// audio loading
	public static boolean autoSteroDownmix;
	public static String[] downMixBlacklist;

	// misc
	public static boolean autoSteroDownmixLogging;
	public static boolean injectorLogging;
	public static boolean debugSoundDirection;

	private static final String categoryGeneral = "General";
	private static final String categoryPerformance = "Performance";
	private static final String categoryMaterialProperties = "Material properties";
	private static final String categoryAudioLoading = "Audio loading";
	private static final String categoryMisc = "Misc";

	static {
		instance = new Config();
	}

	private Config() {
		this.forgeConfig = new Configuration(
				new File(
						new File(CoreModLoader.mcDir, "config"),
						SoundPhysics.modid + ".cfg"
				)
		);
		syncConfig();
	}

	public void preInit(final FMLPreInitializationEvent event) {
		syncConfig();
	}

	public void init(final FMLInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent eventArgs) {
		if (eventArgs.getModID().equals(SoundPhysics.modid)) {
			syncConfig();
		}
	}

	static String getBlacklist() {
		StringBuilder blacklist = new StringBuilder();

		for (String entry : downMixBlacklist) {
			blacklist.append(entry).append("|");
		}

		blacklist.deleteCharAt(blacklist.length() - 1);

		return blacklist.toString();
	}

	public List<IConfigElement> getConfigElements() {
		final ArrayList<IConfigElement> list = new ArrayList<IConfigElement>();

		list.add(new ConfigElement(this.forgeConfig.getCategory(categoryGeneral)));
		list.add(new ConfigElement(this.forgeConfig.getCategory(categoryPerformance)));
		list.add(new ConfigElement(this.forgeConfig.getCategory(categoryMaterialProperties)));
		list.add(new ConfigElement(this.forgeConfig.getCategory(categoryAudioLoading)));
		list.add(new ConfigElement(this.forgeConfig.getCategory(categoryMisc)));

		return list;
	}

	private void syncReverbConfig() {

		reverbAttenuationDistance = this.forgeConfig.getFloat(
				"Reverb Attenuation Distance",
				categoryGeneral,
				0.0f,
				0.0f,
				8192.0f,
				"Minimum reflected acoustic path length required for reverb generation. Set to 0 to disable."
		);

		reverbDistance = this.forgeConfig.getFloat(
				"Reverb Distance",
				categoryGeneral,
				96.0f,
				0.0f,
				8192.0f,
				"Distance in blocks at which reverb send gain reaches zero. Set to 0 to disable distance attenuation."
		);
	}

	private void syncConfig() {

		// General
		rolloffFactor = this.forgeConfig.getFloat(
				"Attenuation Factor",
				categoryGeneral,
				1.0f,
				0.2f,
				1.0f,
				"Affects how quiet a sound gets based on distance. Lower values mean distant sounds are louder. 1.0 is the physically correct value."
		);

		globalVolumeMultiplier = this.forgeConfig.getFloat(
				"Global Volume Multiplier",
				categoryGeneral,
				4.0f,
				0.1f,
				8.0f,
				"The global volume multiplier of all sounds."
		);

		globalReverbGain = this.forgeConfig.getFloat(
				"Global Reverb Gain",
				categoryGeneral,
				1.0f,
				0.1f,
				2.0f,
				"The global volume of simulated reverberations."
		);

		globalReverbBrightness = this.forgeConfig.getFloat(
				"Global Reverb Brightness",
				categoryGeneral,
				1.0f,
				0.1f,
				2.0f,
				"The brightness of reverberation. Higher values result in more high frequencies in reverberation. Lower values give a more muffled sound to the reverb."
		);

		globalBlockAbsorption = this.forgeConfig.getFloat(
				"Global Block Absorption",
				categoryGeneral,
				1.0f,
				0.1f,
				4.0f,
				"The global amount of sound that will be absorbed when traveling through blocks."
		);

		defaultBlockOcclusionFactor = this.forgeConfig.getFloat(
				"Default Block Occlusion Factor",
				categoryGeneral,
				1.0f,
				0.0f,
				10.0f,
				"The default amount of occlusion contributed by a block when no material-specific value applies."
		);

		globalBlockReflectance = this.forgeConfig.getFloat(
				"Global Block Reflectance",
				categoryGeneral,
				1.0f,
				0.1f,
				4.0f,
				"The global amount of sound reflectance energy of all blocks. Lower values result in more conservative reverb simulation with shorter reverb tails. Higher values result in more generous reverb simulation with higher reverb tails."
		);

		soundDistanceAllowance = this.forgeConfig.getFloat(
				"Sound Distance Allowance",
				categoryGeneral,
				4.0f,
				1.0f,
				6.0f,
				"Minecraft won't allow sounds to play past a certain distance. This parameter is a multiplier for how far away a sound source is allowed to be in order for it to actually play. Values too high can cause polyphony issues."
		);

		airAbsorption = this.forgeConfig.getFloat(
				"Air Absorption",
				categoryGeneral,
				1.0f,
				0.0f,
				5.0f,
				"A value controlling the amount that air absorbs high frequencies with distance. A value of 1.0 is physically correct for air with normal humidity and temperature. Higher values mean air will absorb more high frequencies with distance. 0 disables this effect."
		);

		snowAirAbsorptionFactor = this.forgeConfig.getFloat(
				"Max Snow Air Absorption Factor",
				categoryGeneral,
				5.0f,
				0.0f,
				10.0f,
				"The maximum air absorption factor when it's snowing. The real absorption factor will depend on the snow's intensity. Set to 1 or lower to disable"
		);

		underwaterFilter = this.forgeConfig.getFloat(
				"Underwater Filter",
				categoryGeneral,
				0.8f,
				0.0f,
				1.0f,
				"How much sound is filtered when the listener or sound source is underwater. 0.0 means no filter. 1.0 means fully filtered"
		);

		noteBlockEnable = this.forgeConfig.getBoolean(
				"Affect Note Blocks",
				categoryGeneral,
				true,
				"If true, note blocks will be processed."
		);

		maxDistance = this.forgeConfig.getFloat(
				"Max ray distance",
				categoryGeneral,
				256.0f,
				1.0f,
				8192.0f,
				"How far the rays should be traced."
		);

		volumeMulOnlyAffected = this.forgeConfig.getBoolean(
				"Volume Multiplier Only On Affected",
				categoryGeneral,
				true,
				"If true, the global volume multiplier will only be applied to affected sounds (so not to the ui sounds for example)."
		);

		globalEchoMultiplier = this.forgeConfig.getFloat(
				"Global Echo Multiplier",
				categoryGeneral,
				1.0f,
				0.0f,
				2.0f,
				"The global volume multiplier of the echos, put to 0 to disable echos all together"
		);

		soundDirectionEvaluation = this.forgeConfig.getBoolean(
				"Sound Direction Evaluation",
				categoryGeneral,
				true,
				"If true, calculates the apparent sound direction based on reflected sound paths"
		);

		redirectNonOccludedSounds = this.forgeConfig.getBoolean(
				"Skip Redirecting Non-Occluded Sounds",
				categoryGeneral,
				true,
				"If true, sounds with a direct unobstructed path will not have their positions redirected"
		);

		SoundDirectionExclusions.configure(
				this.forgeConfig.getStringList(
						"Sound Direction Exclusions",
						categoryGeneral,
						new String[0],
						"Sound event IDs that must keep their original source position. " +
								"Reflected sound direction redirection is disabled for these sounds. " +
								"Occlusion, filtering and reverberation remain active. " +
								"Format: namespace:sound_event_id. " +
								"Example: minecraft:entity.generic.explode"
				)
		);

		soundDirectionPositionBiasOverrides =
				this.forgeConfig.getStringList(
						"Sound Direction Position Bias",
						categoryGeneral,
						new String[]{
								"minecraft:block.grass.step=85",
								"minecraft:block.stone.step=85",
								"minecraft:block.wood.step=85",
								"minecraft:block.sand.step=85",
								"minecraft:block.gravel.step=85",
								"minecraft:block.snow.step=85",
								"minecraft:block.metal.step=85",
								"minecraft:block.cloth.step=85"
						},
						"Per-sound original position bias. " +
								"Moves the calculated apparent sound position " +
								"towards the original sound source position. " +
								"Format: sound_event_id=percentage. " +
								"0 means normal acoustic positioning, " +
								"50 means halfway towards the original position, " +
								"100 restores the exact original sound position. " +
								"Example: minecraft:block.grass.step=85"
				);

		SoundDirectionPositionBias.configure(
				soundDirectionPositionBiasOverrides
		);


		// Performance
		updateMovingSounds = this.forgeConfig.getBoolean(
				"Update Moving Sounds",
				categoryPerformance,
				false,
				"If true, moving and tickable sound sources will be periodically reevaluated by Sound Physics."
		);

		soundUpdateInterval = this.forgeConfig.getInt(
				"Moving Sound Update Interval",
				categoryPerformance,
				5,
				1,
				200,
				"Number of ticks between acoustic reevaluations of moving sound sources. Lower values increase processing frequency."
		);

		strictOcclusion = this.forgeConfig.getBoolean(
				"Strict Occlusion",
				categoryPerformance,
				false,
				"If true, only one path between the sound source and the listener is used instead of nine"
		);

		soundRateLimits = this.forgeConfig.getStringList(
				"Sound Rate Limits",
				categoryPerformance,
				new String[]{
						"minecraft:weather.rain=0",
						"minecraft:weather.rain.above=0",
						"minecraft:entity.lightning.thunder=0"
				},
				"Maximum acoustic evaluations per sound event per client tick. " +
						"Format: sound_event_id=limit. " +
						"-1 means unlimited, 0 disables acoustic processing, " +
						"and positive values limit the number of evaluations. " +
						"This does not disable actual sound playback."
		);

		SoundRateManager.configure(soundRateLimits);

		occlusionVariation = this.forgeConfig.getFloat(
				"Occlusion Variation",
				categoryPerformance,
				0.35f,
				0.0f,
				16.0f,
				"Higher values mean smaller objects are less likely to occlude sounds. Set to 0 to disable variation for non-block sounds"
		);

		nonFullBlockOcclusionFactor = this.forgeConfig.getFloat(
				"Non-Full Block Occlusion Factor",
				categoryPerformance,
				0.25f,
				0.0f,
				1.0f,
				"If a ray hits a non-solid block face, its occlusion is multiplied by this value."
		);

		maxOcclusionRays = this.forgeConfig.getInt(
				"Max Occlusion Rays",
				categoryPerformance,
				16,
				1,
				128,
				"The maximum number of block intersections evaluated along each direct occlusion path."
		);

		maxOcclusion = this.forgeConfig.getFloat(
				"Max Occlusion",
				categoryPerformance,
				64.0f,
				0.0f,
				1024.0f,
				"The maximum accumulated block occlusion value. Higher values allow thicker walls to contribute to attenuation."
		);

		skipRainOcclusionTracing = this.forgeConfig.getBoolean(
				"Skip Rain Occlusion Tracing",
				categoryPerformance,
				true,
				"If true, rain sound sources won't trace for sound occlusion. This can help performance during rain."
		);

		environmentEvaluationRays = this.forgeConfig.getInt(
				"Environment Evaluation Rays",
				categoryPerformance,
				32,
				8,
				64,
				"The number of rays to trace to determine reverberation for each sound source. More rays provides more consistent tracing results but takes more time to calculate. Decrease this value if you experience lag spikes when sounds play."
		);

		simplerSharedAirspaceSimulation = this.forgeConfig.getBoolean(
				"Simpler Shared Airspace Simulation",
				categoryPerformance,
				false,
				"If true, enables a simpler technique for determining when the player and a sound source share airspace. Might sometimes miss recognizing shared airspace, but it's faster to calculate."
		);


		// Material properties
		stoneReflectivity = this.forgeConfig.getFloat(
				"Stone Reflectivity",
				categoryMaterialProperties,
				0.95f,
				0.0f,
				1.0f,
				"Sound reflectivity for stone blocks."
		);

		woodReflectivity = this.forgeConfig.getFloat(
				"Wood Reflectivity",
				categoryMaterialProperties,
				0.7f,
				0.0f,
				1.0f,
				"Sound reflectivity for wooden blocks."
		);

		groundReflectivity = this.forgeConfig.getFloat(
				"Ground Reflectivity",
				categoryMaterialProperties,
				0.3f,
				0.0f,
				1.0f,
				"Sound reflectivity for ground blocks (dirt, gravel, etc)."
		);

		plantReflectivity = this.forgeConfig.getFloat(
				"Foliage Reflectivity",
				categoryMaterialProperties,
				0.2f,
				0.0f,
				1.0f,
				"Sound reflectivity for foliage blocks (leaves, grass, etc.)."
		);

		metalReflectivity = this.forgeConfig.getFloat(
				"Metal Reflectivity",
				categoryMaterialProperties,
				0.97f,
				0.0f,
				1.0f,
				"Sound reflectivity for metal blocks."
		);

		glassReflectivity = this.forgeConfig.getFloat(
				"Glass Reflectivity",
				categoryMaterialProperties,
				0.5f,
				0.0f,
				1.0f,
				"Sound reflectivity for glass blocks."
		);

		clothReflectivity = this.forgeConfig.getFloat(
				"Cloth Reflectivity",
				categoryMaterialProperties,
				0.25f,
				0.0f,
				1.0f,
				"Sound reflectivity for cloth blocks (carpet, wool, etc)."
		);

		sandReflectivity = this.forgeConfig.getFloat(
				"Sand Reflectivity",
				categoryMaterialProperties,
				0.2f,
				0.0f,
				1.0f,
				"Sound reflectivity for sand blocks."
		);

		snowReflectivity = this.forgeConfig.getFloat(
				"Snow Reflectivity",
				categoryMaterialProperties,
				0.2f,
				0.0f,
				1.0f,
				"Sound reflectivity for snow blocks."
		);

		blockAcousticOverrides = this.forgeConfig.getStringList(
				"Block Acoustic Overrides",
				categoryMaterialProperties,
				new String[0],
				"Per-block acoustic overrides. Format: modid:block=occlusion,reflectivity. " +
						"Occlusion range: 0-10. Reflectivity range: 0-1. " +
						"Overrides SoundType defaults. Global multipliers are still applied."
		);

		BlockAcousticManager.configure(blockAcousticOverrides);


		// Audio loading
		autoSteroDownmix = this.forgeConfig.getBoolean(
				"Auto Stereo Downmix",
				categoryAudioLoading,
				true,
				"REQUIRES RESTART. If true, Automatically downmix stereo sounds that are loaded to mono"
		);

		downMixBlacklist = this.forgeConfig.getStringList(
				"Downmix Blacklist",
				categoryAudioLoading,
				new String[]{
						".*rain.*",
						".*step.*",
						".*block.*",
						".*/ui/.*",
						".*block.note.*",
						".*portal/travel*.*",
						"^soundphysics:.*"
				},
				"REQUIRES RESTART. Regex pattern of downmix blacklist, they will be chained with |"
		);


		// Misc
		autoSteroDownmixLogging = this.forgeConfig.getBoolean(
				"Stereo downmix Logging",
				categoryMisc,
				false,
				"If true, Prints sound name and format of the sounds that get converted"
		);

		injectorLogging = this.forgeConfig.getBoolean(
				"Injector Logging",
				categoryMisc,
				false,
				"If true, Logs debug info about the injector"
		);

		debugSoundDirection = this.forgeConfig.getBoolean(
				"Debug Sound Direction",
				categoryMisc,
				false,
				"Writes detailed text diagnostics for sound direction evaluation to the client log. Includes ray counts, reflection paths, direction coherence and apparent source position."
		);


		syncReverbConfig();
		SoundPhysics.applyConfigChanges();

		if (this.forgeConfig.hasChanged()) {
			this.forgeConfig.save();
		}
	}
}
