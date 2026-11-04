package com.sonicether.soundphysics;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.init.Biomes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Read-only access to a cached area of the client world.

 * The snapshot is assembled on the client thread. Acoustic calculations use
 * this object instead of querying WorldClient and its chunk provider for every
 * ray step.

 * Chunk section references are captured when the snapshot is built.
 * The section objects themselves are shared with their source chunks,
 * following the lightweight cache strategy used by Sound Physics Remastered.
 */
public final class ClientWorldSnapshot implements IBlockAccess {

    private static final int MAX_RAY_STEPS = 200;

    private final Map<Long, ChunkSnapshot> chunks;
    private final WorldType worldType;
    private final boolean raining;
    private final float rainStrength;

    private ClientWorldSnapshot(Map<Long, ChunkSnapshot> chunks,
                                WorldType worldType,
                                boolean raining,
                                float rainStrength) {
        this.chunks = chunks;
        this.worldType = worldType;
        this.raining = raining;
        this.rainStrength = rainStrength;
    }

    @Override
    public boolean isSideSolid(@Nonnull BlockPos pos,@Nonnull EnumFacing side, boolean defaultValue) {
        IBlockState state = getBlockState(pos);

        if (state == null) {
            return defaultValue;
        }

        return state.isSideSolid(this, pos, side);
    }

    public static ClientWorldSnapshot create(WorldClient world, EntityPlayer player, int chunkRange) {
        Map<Long, ChunkSnapshot> cachedChunks = new HashMap<>();

        int centerChunkX = MathHelper.floor(player.posX) >> 4;
        int centerChunkZ = MathHelper.floor(player.posZ) >> 4;
        BiomeProvider biomeProvider = world.getBiomeProvider();

        for (int x = -chunkRange; x < chunkRange; x++) {
            for (int z = -chunkRange; z < chunkRange; z++) {
                int chunkX = centerChunkX + x;
                int chunkZ = centerChunkZ + z;

                // getLoadedChunk does not request or generate a missing chunk.
                Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);

                if (chunk == null) {
                    continue;
                }

                cachedChunks.put(chunkKey(chunkX, chunkZ),
                        new ChunkSnapshot(chunk, chunkX, chunkZ, biomeProvider));
            }
        }

        return new ClientWorldSnapshot(
                cachedChunks,
                world.getWorldType(),
                world.isRaining(),
                world.getRainStrength(1.0f)
        );
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    @Nullable
    private ChunkSnapshot getChunkSnapshot(int chunkX, int chunkZ) {
        return chunks.get(chunkKey(chunkX, chunkZ));
    }

    @Nonnull
    @Override
    public IBlockState getBlockState(BlockPos pos) {
        ChunkSnapshot chunk = getChunkSnapshot(pos.getX() >> 4, pos.getZ() >> 4);

        if (chunk == null) {
            Block airBlock = Blocks.AIR;

            if (airBlock == null) {
                throw new IllegalStateException("Blocks.AIR is not initialized!");
            }

            return airBlock.getDefaultState();
        }

        return chunk.getBlockState(pos);
    }

    @Nonnull
    @Override
    public Biome getBiome(BlockPos pos) {
        ChunkSnapshot chunk = getChunkSnapshot(pos.getX() >> 4, pos.getZ() >> 4);

        if (chunk == null) {
            return Biomes.PLAINS;
        }

        return chunk.getBiome(pos);
    }

    @Nullable
    @Override
    public net.minecraft.tileentity.TileEntity getTileEntity(@Nonnull BlockPos pos) {
        // Sound propagation does not inspect tile entities.
        return null;
    }

    @Override
    public int getCombinedLight(@Nonnull BlockPos pos, int lightValue) {
        return lightValue;
    }

    @Override
    public int getStrongPower(@Nonnull BlockPos pos,@Nonnull EnumFacing direction) {
        return getBlockState(pos).getStrongPower(this, pos, direction);
    }

    @Nonnull
    @Override
    public WorldType getWorldType() {
        return worldType;
    }

    @Override
    public boolean isAirBlock(@Nonnull BlockPos pos) {
        return getBlockState(pos).getMaterial() == Material.AIR;
    }

    public boolean isRaining() {
        return raining;
    }

    public float getRainStrength() {
        return rainStrength;
    }

    public boolean canSeeSky(BlockPos pos) {
        ChunkSnapshot chunk = getChunkSnapshot(pos.getX() >> 4, pos.getZ() >> 4);

        return chunk != null && chunk.canSeeSky(pos);
    }

    public BlockPos getPrecipitationHeight(BlockPos pos) {
        ChunkSnapshot chunk = getChunkSnapshot(pos.getX() >> 4, pos.getZ() >> 4);

        if (chunk == null) {
            return new BlockPos(pos.getX(), 256, pos.getZ());
        }

        return new BlockPos(pos.getX(), chunk.getPrecipitationHeight(pos), pos.getZ());
    }

    public boolean canSnowAt(BlockPos pos) {
        return getBiome(pos).getTemperature(pos) < 0.15F;
    }

    /**
     * Ray trace through the cached block states.
     * This uses the vanilla block bounding box for each visited position.
     * Blocks that replace vanilla collisionRayTrace with custom World-specific
     * logic cannot run that override against this IBlockAccess-only snapshot.
     */
    @SuppressWarnings("all")
    @Nullable
    public RayTraceResult rayTraceBlocks(Vec3d start, Vec3d end, boolean stopOnLiquid) {
        if (Double.isNaN(start.x) || Double.isNaN(start.y) || Double.isNaN(start.z) ||
                Double.isNaN(end.x) || Double.isNaN(end.y) || Double.isNaN(end.z)) {
            return null;
        }

        int x = MathHelper.floor(start.x);
        int y = MathHelper.floor(start.y);
        int z = MathHelper.floor(start.z);

        int endX = MathHelper.floor(end.x);
        int endY = MathHelper.floor(end.y);
        int endZ = MathHelper.floor(end.z);

        RayTraceResult hit = rayTraceBlock(new BlockPos(x, y, z), start, end, stopOnLiquid);

        if (hit != null) {
            return hit;
        }

        double deltaX = end.x - start.x;
        double deltaY = end.y - start.y;
        double deltaZ = end.z - start.z;

        int stepX = deltaX > 0.0D ? 1 : deltaX < 0.0D ? -1 : 0;
        int stepY = deltaY > 0.0D ? 1 : deltaY < 0.0D ? -1 : 0;
        int stepZ = deltaZ > 0.0D ? 1 : deltaZ < 0.0D ? -1 : 0;

        double tMaxX = stepX > 0 ? (x + 1.0D - start.x) / deltaX
                : stepX < 0 ? (x - start.x) / deltaX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1.0D - start.y) / deltaY
                : stepY < 0 ? (y - start.y) / deltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1.0D - start.z) / deltaZ
                : stepZ < 0 ? (z - start.z) / deltaZ : Double.POSITIVE_INFINITY;

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / deltaX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / deltaY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / deltaZ);

        for (int steps = 0; steps < MAX_RAY_STEPS; steps++) {
            if (x == endX && y == endY && z == endZ) {
                return null;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }

            hit = rayTraceBlock(new BlockPos(x, y, z), start, end, stopOnLiquid);

            if (hit != null) {
                return hit;
            }
        }

        return null;
    }

    @Nullable
    private RayTraceResult rayTraceBlock(BlockPos pos, Vec3d start, Vec3d end, boolean stopOnLiquid) {
        IBlockState state = getBlockState(pos);
        Block block = state.getBlock();

        if (!block.canCollideCheck(state, stopOnLiquid)) {
            return null;
        }

        AxisAlignedBB box = state.getBoundingBox(this, pos);

        if (box == null || box == Block.NULL_AABB) {
            return null;
        }

        Vec3d localStart = new Vec3d(
                start.x - pos.getX(),
                start.y - pos.getY(),
                start.z - pos.getZ()
        );

        Vec3d localEnd = new Vec3d(
                end.x - pos.getX(),
                end.y - pos.getY(),
                end.z - pos.getZ()
        );

        RayTraceResult localHit = box.calculateIntercept(localStart, localEnd);

        if (localHit == null) {
            return null;
        }

        Vec3d hitVec = new Vec3d(
                localHit.hitVec.x + pos.getX(),
                localHit.hitVec.y + pos.getY(),
                localHit.hitVec.z + pos.getZ()
        );

        return new RayTraceResult(hitVec, localHit.sideHit, pos);
    }

    private static final class ChunkSnapshot {

        private final ExtendedBlockStorage[] sections;
        private final Biome[] biomes;
        private final int[] heightMap;
        private final AtomicIntegerArray precipitationHeightMap;

        private ChunkSnapshot(Chunk chunk, int chunkX, int chunkZ, BiomeProvider biomeProvider) {
            // Section objects are shared; the section array itself is captured.
            this.sections = chunk.getBlockStorageArray().clone();
            this.heightMap = chunk.getHeightMap().clone();
            this.precipitationHeightMap = new AtomicIntegerArray(256);
            this.biomes = new Biome[256];

            byte[] biomeIds = chunk.getBiomeArray().clone();

            // Capture biome IDs on the client thread; precipitation columns are lazy.
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = z * 16 + x;
                    int biomeId = biomeIds[index] & 255;
                    Biome biome = Biome.getBiome(biomeId);

                    if (biomeId == 255 || biome == null) {
                        BlockPos biomePos = new BlockPos(chunkX * 16 + x, 0, chunkZ * 16 + z);
                        biome = biomeProvider.getBiome(biomePos, Biomes.PLAINS);
                    }

                    this.biomes[index] = biome == null ? Biomes.PLAINS : biome;
                }
            }
        }

        private IBlockState getBlockState(BlockPos pos) {
            int sectionY = pos.getY() >> 4;

            for (ExtendedBlockStorage section : sections) {
                if (section != null && (section.getYLocation() >> 4) == sectionY) {
                    return section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                }
            }

            return Blocks.AIR.getDefaultState();
        }

        private Biome getBiome(BlockPos pos) {
            int index = (pos.getZ() & 15) * 16 + (pos.getX() & 15);
            Biome biome = biomes[index];
            return biome == null ? Biomes.PLAINS : biome;
        }

        private boolean canSeeSky(BlockPos pos) {
            int index = (pos.getZ() & 15) * 16 + (pos.getX() & 15);
            return pos.getY() >= heightMap[index];
        }

        private int getPrecipitationHeight(BlockPos pos) {
            int index = (pos.getZ() & 15) * 16 + (pos.getX() & 15);
            int cached = precipitationHeightMap.get(index);

            if (cached != 0) {
                return cached - 1;
            }

            // Compute this column lazily from the cached block sections.
            // Vanilla's precipitation height is the first solid/liquid block + 1.
            int height = 0;
            int x = pos.getX();
            int z = pos.getZ();
            int maxY = 255;

            for (ExtendedBlockStorage section : sections) {
                if (section != null) {
                    maxY = Math.max(maxY, section.getYLocation() + 15);
                }
            }

            for (int y = maxY; y > 0; y--) {
                Material material = getBlockState(new BlockPos(x, y, z)).getMaterial();

                if (material.blocksMovement() || material.isLiquid()) {
                    height = y + 1;
                    break;
                }
            }

            precipitationHeightMap.compareAndSet(index, 0, height + 1);
            return precipitationHeightMap.get(index) - 1;
        }
    }
}
