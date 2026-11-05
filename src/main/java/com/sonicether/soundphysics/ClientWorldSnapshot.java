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


    private final Map<Long, ChunkSnapshot> chunks;
    private final WorldType worldType;
    private final boolean raining;
    private final float rainStrength;

    private static final int MAX_RAY_TRACE_STEPS = 32768;

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

    /**
     * Checks whether the chunk containing the specified position
     * is available in this snapshot.
     */
    public boolean hasChunkAt(BlockPos pos) {
        return getChunkSnapshot(
                pos.getX() >> 4,
                pos.getZ() >> 4
        ) != null;
    }

    /**
     * Checks whether the complete horizontal path is covered
     * by cached chunks.
     * <p>
     * Missing chunks must not be treated as open air when
     * calculating sound direction
     */


    public boolean hasChunkCoverage(
            Vec3d start,
            Vec3d end
    ) {

        if (!isTraceWithinLimit(start, end)) {
            return false;
        }

        int chunkX = MathHelper.floor(start.x) >> 4;
        int chunkZ = MathHelper.floor(start.z) >> 4;

        final int targetChunkX =
                MathHelper.floor(end.x) >> 4;

        final int targetChunkZ =
                MathHelper.floor(end.z) >> 4;

        if (getChunkSnapshot(chunkX, chunkZ) == null ||
                getChunkSnapshot(targetChunkX, targetChunkZ) == null) {
            return false;
        }

        if (chunkX == targetChunkX &&
                chunkZ == targetChunkZ) {
            return true;
        }

        final double dx = end.x - start.x;
        final double dz = end.z - start.z;

        final int stepX = dx > 0.0D
                ? 1
                : dx < 0.0D ? -1 : 0;

        final int stepZ = dz > 0.0D
                ? 1
                : dz < 0.0D ? -1 : 0;

        double maxDistanceX = Double.POSITIVE_INFINITY;
        double maxDistanceZ = Double.POSITIVE_INFINITY;

        double distanceDeltaX = Double.POSITIVE_INFINITY;
        double distanceDeltaZ = Double.POSITIVE_INFINITY;

        if (stepX != 0) {

            final double nextBoundaryX = stepX > 0
                    ? ((double) chunkX + 1.0D) * 16.0D
                    : (double) chunkX * 16.0D;

            maxDistanceX =
                    (nextBoundaryX - start.x) / dx;

            distanceDeltaX =
                    16.0D / Math.abs(dx);
        }

        if (stepZ != 0) {

            final double nextBoundaryZ = stepZ > 0
                    ? ((double) chunkZ + 1.0D) * 16.0D
                    : (double) chunkZ * 16.0D;

            maxDistanceZ =
                    (nextBoundaryZ - start.z) / dz;

            distanceDeltaZ =
                    16.0D / Math.abs(dz);
        }

        final long maximumSteps =
                Math.abs((long) targetChunkX - chunkX) +
                        Math.abs((long) targetChunkZ - chunkZ) +
                        2L;

        for (long i = 0; i < maximumSteps; i++) {

            if (chunkX == targetChunkX &&
                    chunkZ == targetChunkZ) {
                return true;
            }

            /*
             * Match rayTraceBlocks() axis priority:
             * Z is selected when X and Z intersections are equal.
             */
            if (stepZ != 0 &&
                    (stepX == 0 || maxDistanceZ <= maxDistanceX)) {

                chunkZ += stepZ;

                maxDistanceZ += distanceDeltaZ;

            } else if (stepX != 0) {

                chunkX += stepX;

                maxDistanceX += distanceDeltaX;

            } else {

                return false;
            }

            if (getChunkSnapshot(chunkX, chunkZ) == null) {
                return false;
            }
        }

        return chunkX == targetChunkX &&
                chunkZ == targetChunkZ;
    }


    /**
     * Checks whether a ray can be completely processed by the DDA tracer.
     * <p>
     * The maximum number of visited block cells is bounded by the sum
     * of the coordinate differences plus a small boundary allowance.
     */
    private static boolean isTraceWithinLimit(
            Vec3d start,
            Vec3d end
    ) {

        if (start == null || end == null) {
            return false;
        }

        final double dx = Math.abs(end.x - start.x);
        final double dy = Math.abs(end.y - start.y);
        final double dz = Math.abs(end.z - start.z);

        if (Double.isNaN(dx) ||
                Double.isNaN(dy) ||
                Double.isNaN(dz) ||
                Double.isInfinite(dx) ||
                Double.isInfinite(dy) ||
                Double.isInfinite(dz)) {
            return false;
        }

        final double requiredSteps =
                Math.ceil(dx) +
                        Math.ceil(dy) +
                        Math.ceil(dz) +
                        3.0D;

        return requiredSteps <= MAX_RAY_TRACE_STEPS;
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
    @Nullable
    public RayTraceResult rayTraceBlocks(
            Vec3d start,
            Vec3d end,
            boolean stopOnLiquid
    ) {
        return rayTraceBlocks(
                start,
                end,
                stopOnLiquid,
                null
        );
    }


    @Nullable
    public RayTraceResult rayTraceBlocks(
            Vec3d start,
            Vec3d end,
            boolean stopOnLiquid,
            @Nullable BlockPos ignoredBlock
    ) {

        if (!isTraceWithinLimit(start, end)) {
            return null;
        }

        final int endX = MathHelper.floor(end.x);
        final int endY = MathHelper.floor(end.y);
        final int endZ = MathHelper.floor(end.z);

        int x = MathHelper.floor(start.x);
        int y = MathHelper.floor(start.y);
        int z = MathHelper.floor(start.z);

        if (getChunkSnapshot(x >> 4, z >> 4) == null) {
            return null;
        }

        Vec3d currentStart = start;

        BlockPos currentBlock = new BlockPos(x, y, z);

        /*
         * Check the block containing the initial ray position.
         * The previously reflected block can be explicitly ignored.
         */
        if (!currentBlock.equals(ignoredBlock)) {

            RayTraceResult initialHit = rayTraceBlock(
                    currentBlock,
                    currentStart,
                    end,
                    stopOnLiquid
            );

            if (initialHit != null) {
                return initialHit;
            }
        }

        final double totalDistance =
                Math.ceil(Math.abs(end.x - start.x)) +
                        Math.ceil(Math.abs(end.y - start.y)) +
                        Math.ceil(Math.abs(end.z - start.z)) +
                        3.0D;

        final int maximumSteps =
                (int) totalDistance;

        /*
         * Traverse one block boundary per iteration.
         *
         * Unlike the previous fixed 200-step limit, this limit
         * scales with the actual ray length.
         */
        for (int steps = 0; steps < maximumSteps; steps++) {

            if (x == endX &&
                    y == endY &&
                    z == endZ) {
                return null;
            }

            final boolean advanceX = endX != x;
            final boolean advanceY = endY != y;
            final boolean advanceZ = endZ != z;

            final double boundaryX = endX > x
                    ? (double) x + 1.0D
                    : (double) x;

            final double boundaryY = endY > y
                    ? (double) y + 1.0D
                    : (double) y;

            final double boundaryZ = endZ > z
                    ? (double) z + 1.0D
                    : (double) z;

            final double deltaX = end.x - currentStart.x;
            final double deltaY = end.y - currentStart.y;
            final double deltaZ = end.z - currentStart.z;

            double distanceX = advanceX
                    ? (boundaryX - currentStart.x) / deltaX
                    : Double.POSITIVE_INFINITY;

            double distanceY = advanceY
                    ? (boundaryY - currentStart.y) / deltaY
                    : Double.POSITIVE_INFINITY;

            double distanceZ = advanceZ
                    ? (boundaryZ - currentStart.z) / deltaZ
                    : Double.POSITIVE_INFINITY;

            /*
             * When starting exactly on a boundary and moving
             * in the negative direction, the intersection distance
             * can be negative zero.
             */
            if (distanceX == 0.0D) {
                distanceX = -1.0E-4D;
            }

            if (distanceY == 0.0D) {
                distanceY = -1.0E-4D;
            }

            if (distanceZ == 0.0D) {
                distanceZ = -1.0E-4D;
            }

            final EnumFacing enteredSide;

            /*
             * Preserve the existing axis priority:
             *
             * X if strictly closest,
             * otherwise Y if strictly closer than Z,
             * otherwise Z.
             */
            if (distanceX < distanceY &&
                    distanceX < distanceZ) {

                enteredSide = endX > x
                        ? EnumFacing.WEST
                        : EnumFacing.EAST;

                currentStart = new Vec3d(
                        boundaryX,
                        currentStart.y + deltaY * distanceX,
                        currentStart.z + deltaZ * distanceX
                );

            } else if (distanceY < distanceZ) {

                enteredSide = endY > y
                        ? EnumFacing.DOWN
                        : EnumFacing.UP;

                currentStart = new Vec3d(
                        currentStart.x + deltaX * distanceY,
                        boundaryY,
                        currentStart.z + deltaZ * distanceY
                );

            } else {

                enteredSide = endZ > z
                        ? EnumFacing.NORTH
                        : EnumFacing.SOUTH;

                currentStart = new Vec3d(
                        currentStart.x + deltaX * distanceZ,
                        currentStart.y + deltaY * distanceZ,
                        boundaryZ
                );
            }

            /*
             * Determine the newly entered block.
             *
             * Subtract one block on the positive-facing side
             * when moving in a negative direction.
             */
            x = MathHelper.floor(currentStart.x)
                    - (enteredSide == EnumFacing.EAST ? 1 : 0);

            y = MathHelper.floor(currentStart.y)
                    - (enteredSide == EnumFacing.UP ? 1 : 0);

            z = MathHelper.floor(currentStart.z)
                    - (enteredSide == EnumFacing.SOUTH ? 1 : 0);

            currentBlock = new BlockPos(x, y, z);

            /*
             * Never interpret unavailable chunks as air.
             */
            if (getChunkSnapshot(x >> 4, z >> 4) == null) {
                return null;
            }

            /*
             * Skip the block from which the ray was reflected.
             */
            if (currentBlock.equals(ignoredBlock)) {
                continue;
            }

            RayTraceResult hit = rayTraceBlock(
                    currentBlock,
                    currentStart,
                    end,
                    stopOnLiquid
            );

            if (hit != null) {
                return hit;
            }
        }

        return null;
    }


    @Nullable
    private RayTraceResult rayTraceBlock(
            BlockPos pos,
            Vec3d start,
            Vec3d end,
            boolean stopOnLiquid
    ) {
        IBlockState state = getBlockState(pos);

        Block block = state.getBlock();

        if (!block.canCollideCheck(state, stopOnLiquid)) {
            return null;
        }

        /*
         * Vanilla 1.12.2 Block#collisionRayTrace uses
         * the block's getBoundingBox().
         *
         * Previously we tested both collision bounding box
         * and selection bounding box, then selected whichever
         * intersection was closer.
         *
         * That is not equivalent to vanilla ray tracing.
         */
        AxisAlignedBB boundingBox = state.getBoundingBox(
                this,
                pos
        );

        return rayTraceBox(
                pos,
                boundingBox,
                start,
                end
        );
    }

    @Nullable
    private RayTraceResult rayTraceBox(
            BlockPos pos,
            AxisAlignedBB box,
            Vec3d start,
            Vec3d end
    ) {
        if (box == null || box == Block.NULL_AABB) {
            return null;
        }

        /*
         * Block bounding boxes use local block coordinates.
         */
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

        RayTraceResult localHit = box.calculateIntercept(
                localStart,
                localEnd
        );

        if (localHit == null) {
            return null;
        }

        Vec3d hitVec = new Vec3d(
                localHit.hitVec.x + pos.getX(),
                localHit.hitVec.y + pos.getY(),
                localHit.hitVec.z + pos.getZ()
        );

        return new RayTraceResult(
                hitVec,
                localHit.sideHit,
                pos
        );
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
