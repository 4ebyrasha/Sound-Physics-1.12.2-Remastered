
package com.sonicether.soundphysics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class SoundDirectionDebug {

    public static final int COLOR_SOURCE = 0xFFFFA000;
    public static final int COLOR_LISTENER = 0xFFFFFFFF;

    public static final int COLOR_DIRECT_CLEAR = 0xFFFFFFFF;
    public static final int COLOR_PRIMARY = 0xFFFFFF00;
    public static final int COLOR_BOUNCE = 0xFF00FFFF;

    public static final int COLOR_VALID = 0xFF00FF00;
    public static final int COLOR_BLOCKED = 0xFFFF3030;
    public static final int COLOR_UNKNOWN = 0xFFFF8000;

    public static final int COLOR_APPARENT = 0xFFFF00FF;
    public static final int COLOR_EXPECTED = 0xFF0080FF;

    private static final int RAY_LIFESPAN_TICKS = 40;

    private static final double MAX_RENDER_DISTANCE_SQUARED =
            32.0D * 32.0D;

    private static final List<DebugRay> RAYS =
            new ArrayList<>();

    private static volatile boolean registered;

    private SoundDirectionDebug() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        MinecraftForge.EVENT_BUS.register(
                new SoundDirectionDebug()
        );

        registered = true;
    }

    public static Builder begin(
            WorldClient world,
            Vec3d source,
            Vec3d listener,
            String soundName
    ) {
        return new Builder(
                Config.debugSoundDirection,
                world,
                source,
                listener
        );
    }

    /**
     * Adds an occlusion ray.
     *
     * Occlusion rays are rendered through blocks.
     * This matches the throughWalls behavior of the original
     * Sound Physics Remastered RaycastRenderer.
     */
    public static void addOcclusionRay(
            WorldClient world,
            Vec3d start,
            Vec3d end,
            int color
    ) {
        addRay(
                world,
                start,
                end,
                color,
                true
        );
    }

    /**
     * Adds a single ray to the shared renderer list.
     */
    private static void addRay(
            WorldClient world,
            Vec3d start,
            Vec3d end,
            int color,
            boolean throughWalls
    ) {
        if (!Config.debugSoundDirection ||
                world == null ||
                start == null ||
                end == null) {
            return;
        }

        DebugRay ray = new DebugRay(
                world,
                start,
                end,
                color,
                world.getTotalWorldTime(),
                throughWalls
        );

        synchronized (RAYS) {
            RAYS.add(ray);
        }
    }

    public static final class Builder {

        private final boolean enabled;
        private final Vec3d listener;

        private final WorldClient world;

        private final List<DebugLine> lines =
                new ArrayList<>();

        private Builder(
                boolean enabled,
                WorldClient world,
                Vec3d source,
                Vec3d listener
        ) {
            this.enabled = enabled;
            this.world = world;
            this.listener = listener;

            if (enabled) {
                addMarker(
                        source,
                        COLOR_SOURCE,
                        0.35D
                );

                addMarker(
                        listener,
                        COLOR_LISTENER,
                        0.35D
                );
            }
        }

        public void addLine(
                Vec3d from,
                Vec3d to,
                int color
        ) {
            addLine(
                    from,
                    to,
                    color,
                    false
            );
        }

        /**
         * Adds a line with an explicit depth-test mode.
         * <p>
         * throughWalls = false:
         * The line is hidden behind blocks.
         * <p>
         * throughWalls = true:
         * The line is rendered over blocks.
         */
        public void addLine(
                Vec3d from,
                Vec3d to,
                int color,
                boolean throughWalls
        ) {
            if (!enabled || from == null || to == null) {
                return;
            }

            lines.add(
                    new DebugLine(
                            from,
                            to,
                            color,
                            throughWalls
                    )
            );
        }

        /**
         * Draws a 3D cross at the specified position.
         */
        public void addMarker(
                Vec3d position,
                int color,
                double size
        ) {
            if (!enabled || position == null) {
                return;
            }

            addLine(
                    position.add(-size, 0.0D, 0.0D),
                    position.add(size, 0.0D, 0.0D),
                    color
            );

            addLine(
                    position.add(0.0D, -size, 0.0D),
                    position.add(0.0D, size, 0.0D),
                    color
            );

            addLine(
                    position.add(0.0D, 0.0D, -size),
                    position.add(0.0D, 0.0D, size),
                    color
            );
        }

        /**
         * Publishes every collected line.
         * <p>
         * Unlike the previous implementation, this does not
         * replace or lock an existing debug frame.
         * <p>
         * Multiple sounds can be displayed simultaneously.
         */
        public void finish(Vec3d apparentPosition) {
            if (!enabled || world == null) {
                return;
            }

            if (apparentPosition != null) {
                addLine(
                        listener,
                        apparentPosition,
                        COLOR_APPARENT
                );

                addMarker(
                        apparentPosition,
                        COLOR_APPARENT,
                        0.5D
                );
            }

            long createdAt = world.getTotalWorldTime();

            synchronized (RAYS) {
                for (DebugLine line : lines) {
                    RAYS.add(
                            new DebugRay(
                                    world,
                                    line.from,
                                    line.to,
                                    line.color,
                                    createdAt,
                                    line.throughWalls
                            )
                    );
                }
            }
        }
    }

    private static final class DebugLine {

        private final Vec3d from;
        private final Vec3d to;

        private final int color;

        private final boolean throughWalls;

        private DebugLine(
                Vec3d from,
                Vec3d to,
                int color,
                boolean throughWalls
        ) {
            this.from = from;
            this.to = to;
            this.color = color;
            this.throughWalls = throughWalls;
        }
    }

    private static final class DebugRay {

        private final WorldClient world;

        private final Vec3d start;
        private final Vec3d end;

        private final int color;

        private final long createdAt;

        private final boolean throughWalls;

        private DebugRay(
                WorldClient world,
                Vec3d start,
                Vec3d end,
                int color,
                long createdAt,
                boolean throughWalls
        ) {
            this.world = world;
            this.start = start;
            this.end = end;
            this.color = color;
            this.createdAt = createdAt;
            this.throughWalls = throughWalls;
        }
    }


    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {

        if (!Config.debugSoundDirection) {
            synchronized (RAYS) {
                RAYS.clear();
            }
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.world == null) {
            synchronized (RAYS) {
                RAYS.clear();
            }
            return;
        }

        RenderManager renderManager = mc.getRenderManager();

        final double renderX = renderManager.viewerPosX;
        final double renderY = renderManager.viewerPosY;
        final double renderZ = renderManager.viewerPosZ;

        final long currentTick = mc.world.getTotalWorldTime();

        List<DebugRay> visibleRays = new ArrayList<>();

        synchronized (RAYS) {
            Iterator<DebugRay> iterator = RAYS.iterator();

            while (iterator.hasNext()) {
                DebugRay ray = iterator.next();

                long age = currentTick - ray.createdAt;

                if (ray.world != mc.world ||
                        age < 0L ||
                        age > RAY_LIFESPAN_TICKS) {
                    iterator.remove();
                    continue;
                }

                if (isWithinRenderDistance(
                        ray,
                        renderX,
                        renderY,
                        renderZ
                )) {
                    visibleRays.add(ray);
                }
            }
        }

        if (visibleRays.isEmpty()) {
            return;
        }

        // Save the active matrix mode.
        final int previousMatrixMode =
                GL11.glGetInteger(GL11.GL_MATRIX_MODE);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        // IMPORTANT: world coordinates must use MODELVIEW.
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();

        try {
            // Translate world coordinates relative to the camera.
            GL11.glTranslated(
                    -renderX,
                    -renderY,
                    -renderZ
            );

            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);

            GL11.glEnable(GL11.GL_BLEND);

            GL11.glBlendFunc(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA
            );

            // Do not modify the world's depth buffer.
            GL11.glDepthMask(false);

            GL11.glLineWidth(2.0F);

            // Normal rays: occluded by blocks.
            GL11.glEnable(GL11.GL_DEPTH_TEST);

            drawRays(visibleRays, false);

            // Occlusion rays: visible through blocks.
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            drawRays(visibleRays, true);

        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();

            GL11.glPopAttrib();

            GL11.glMatrixMode(previousMatrixMode);
        }
    }


    private static boolean isWithinRenderDistance(
            DebugRay ray,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        return distanceSquared(
                ray.start,
                cameraX,
                cameraY,
                cameraZ
        ) <= MAX_RENDER_DISTANCE_SQUARED ||

                distanceSquared(
                        ray.end,
                        cameraX,
                        cameraY,
                        cameraZ
                ) <= MAX_RENDER_DISTANCE_SQUARED;
    }

    private static double distanceSquared(
            Vec3d position,
            double x,
            double y,
            double z
    ) {
        double dx = position.x - x;
        double dy = position.y - y;
        double dz = position.z - z;

        return dx * dx + dy * dy + dz * dz;
    }

    private static void drawRays(
            List<DebugRay> rays,
            boolean throughWalls
    ) {
        boolean hasRays = false;

        for (DebugRay ray : rays) {
            if (ray.throughWalls == throughWalls) {
                hasRays = true;
                break;
            }
        }

        if (!hasRays) {
            return;
        }

        Tessellator tessellator =
                Tessellator.getInstance();

        BufferBuilder buffer =
                tessellator.getBuffer();

        buffer.begin(
                GL11.GL_LINES,
                DefaultVertexFormats.POSITION_COLOR
        );

        for (DebugRay ray : rays) {

            if (ray.throughWalls != throughWalls) {
                continue;
            }

            addVertex(
                    buffer,
                    ray.start,
                    ray.color
            );

            addVertex(
                    buffer,
                    ray.end,
                    ray.color
            );
        }

        tessellator.draw();
    }

    private static void addVertex(
            BufferBuilder buffer,
            Vec3d position,
            int color
    ) {
        int alpha = (color >>> 24) & 255;
        int red = (color >>> 16) & 255;
        int green = (color >>> 8) & 255;
        int blue = color & 255;

        // Positions are absolute world coordinates.
        // The camera translation is applied by the render matrix.
        buffer.pos(
                position.x,
                position.y,
                position.z
        ).color(
                red,
                green,
                blue,
                alpha
        ).endVertex();
    }
}
