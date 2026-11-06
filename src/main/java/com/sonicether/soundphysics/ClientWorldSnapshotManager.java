package com.sonicether.soundphysics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the read-only sound world cache on the client thread and publishes
 * completed snapshots to the sound-processing thread.
 */
public final class ClientWorldSnapshotManager {

    private static final AtomicReference<ClientWorldSnapshot> SNAPSHOT = new AtomicReference<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static final int CHUNK_RANGE = 17;
    private static final long CACHE_RETAIN_TICKS = 20L;
    private static final double CACHE_MOVE_DISTANCE_SQUARED = 256.0D;

    private WorldClient lastWorld;
    private BlockPos lastOrigin;
    private long lastUpdateTick = Long.MIN_VALUE;

    private ClientWorldSnapshotManager() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.register(new ClientWorldSnapshotManager());
        }
    }

    public static ClientWorldSnapshot getSnapshot() {
        return SNAPSHOT.get();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        WorldClient world = minecraft.world;
        EntityPlayer player = minecraft.player;

        if (world == null || player == null) {
            SNAPSHOT.set(null);
            lastWorld = null;
            lastOrigin = null;
            lastUpdateTick = Long.MIN_VALUE;
            return;
        }

        BlockPos origin = new BlockPos(
                MathHelper.floor(player.posX),
                MathHelper.floor(player.posY),
                MathHelper.floor(player.posZ)
        );

        long currentTick = world.getTotalWorldTime();

        boolean worldChanged = world != lastWorld;
        boolean playerMoved = lastOrigin == null ||
                distanceSquared(lastOrigin, origin) >= CACHE_MOVE_DISTANCE_SQUARED;
        boolean cacheExpired = currentTick - lastUpdateTick >= CACHE_RETAIN_TICKS;

        if (!worldChanged && !playerMoved && !cacheExpired && SNAPSHOT.get() != null) {
            return;
        }

        // Build the cache completely before publishing it to sound threads.
        ClientWorldSnapshot newSnapshot = ClientWorldSnapshot.create(world, player, CHUNK_RANGE);
        SNAPSHOT.set(newSnapshot);

        lastWorld = world;
        lastOrigin = origin;
        lastUpdateTick = currentTick;
    }

    private static double distanceSquared(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
