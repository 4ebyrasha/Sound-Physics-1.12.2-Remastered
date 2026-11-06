package com.sonicether.soundphysics;

import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class BlockAcousticManager {

    private static final Logger LOGGER =
            LogManager.getLogger("SoundPhysics");

    private static volatile Map<Block, BlockAcousticProperties> overrides =
            Collections.emptyMap();

    private BlockAcousticManager() {
    }

    public static void configure(String[] entries) {
        final Map<Block, BlockAcousticProperties> newOverrides =
                new HashMap<Block, BlockAcousticProperties>();

        if (entries != null) {
            for (String entry : entries) {
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }

                try {
                    final String[] keyValue = entry.split("=", 2);

                    if (keyValue.length != 2) {
                        throw new IllegalArgumentException(
                                "Expected block_id=occlusion,reflectivity"
                        );
                    }

                    final ResourceLocation blockId =
                            new ResourceLocation(keyValue[0].trim());

                    final Block block = Block.REGISTRY.getObject(blockId);

                    if (block == null ||
                            !blockId.equals(Block.REGISTRY.getNameForObject(block))) {
                        LOGGER.warn(
                                "Unknown block in acoustic override: {}",
                                blockId
                        );
                        continue;
                    }

                    final String[] values = keyValue[1].split(",", 2);

                    if (values.length != 2) {
                        throw new IllegalArgumentException(
                                "Expected two values: occlusion,reflectivity"
                        );
                    }

                    final float occlusion =
                            Float.parseFloat(values[0].trim());

                    final float reflectivity =
                            Float.parseFloat(values[1].trim());

                    if (Float.isNaN(occlusion) ||
                            Float.isInfinite(occlusion) ||
                            Float.isNaN(reflectivity) ||
                            Float.isInfinite(reflectivity)) {
                        throw new IllegalArgumentException(
                                "Values must be finite numbers"
                        );
                    }

                    newOverrides.put(
                            block,
                            new BlockAcousticProperties(
                                    Math.max(0.0f, Math.min(10.0f, occlusion)),
                                    Math.max(0.0f, Math.min(1.0f, reflectivity))
                            )
                    );

                } catch (Exception e) {
                    LOGGER.warn(
                            "Invalid block acoustic override '{}': {}",
                            entry,
                            e.getMessage()
                    );
                }
            }
        }

        overrides = Collections.unmodifiableMap(newOverrides);

        LOGGER.info(
                "Loaded {} block acoustic overrides",
                overrides.size()
        );
    }

    public static Float getOcclusion(Block block) {
        final BlockAcousticProperties properties = overrides.get(block);

        return properties == null ? null : properties.occlusion;
    }

    public static Float getReflectivity(Block block) {
        final BlockAcousticProperties properties = overrides.get(block);

        return properties == null ? null : properties.reflectivity;
    }

    private static final class BlockAcousticProperties {

        private final float occlusion;
        private final float reflectivity;

        private BlockAcousticProperties(
                float occlusion,
                float reflectivity
        ) {
            this.occlusion = occlusion;
            this.reflectivity = reflectivity;
        }
    }
}
