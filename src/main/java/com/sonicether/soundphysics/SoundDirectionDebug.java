
package com.sonicether.soundphysics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final long DISPLAY_TIME_MS = 20000L;

    private static final AtomicReference<DebugFrame> FRAME =
            new AtomicReference<>();

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
                listener,
                soundName
        );
    }

    public static final class Builder {

        private final boolean enabled;
        private final WorldClient world;

        private final Vec3d source;
        private final Vec3d listener;

        private final String soundName;

        private final List<DebugLine> lines =
                new ArrayList<>();

        private Builder(
                boolean enabled,
                WorldClient world,
                Vec3d source,
                Vec3d listener,
                String soundName
        ) {
            this.enabled = enabled;
            this.world = world;
            this.source = source;
            this.listener = listener;
            this.soundName = soundName;

            if (enabled) {
                addMarker(source, COLOR_SOURCE, 0.35D);
                addMarker(listener, COLOR_LISTENER, 0.35D);
            }
        }

        public void addLine(
                Vec3d from,
                Vec3d to,
                int color
        ) {
            if (!enabled || from == null || to == null) {
                return;
            }

            lines.add(new DebugLine(from, to, color));
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
         * Publishes the completed debug frame.
         * <p>
         * The renderer will draw this frame on the next world render
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

            long now = System.currentTimeMillis();

            DebugFrame current = FRAME.get();

            // Keep the first captured sound during the display interval.
            if (current != null &&
                    current.world == world &&
                    now <= current.expiresAt) {
                return;
            }

            DebugFrame next = new DebugFrame(
                    world,
                    new ArrayList<>(lines),
                    soundName,
                    now + DISPLAY_TIME_MS
            );

            FRAME.set(next);

            SoundPhysics.logger.info(
                    "[Sound Direction Debug] Captured sound: {}",
                    soundName
            );
        }
    }

    private static final class DebugLine {

        private final Vec3d from;
        private final Vec3d to;
        private final int color;

        private DebugLine(
                Vec3d from,
                Vec3d to,
                int color
        ) {
            this.from = from;
            this.to = to;
            this.color = color;
        }
    }

    private static final class DebugFrame {

        private final WorldClient world;
        private final List<DebugLine> lines;
        private final String soundName;
        private final long expiresAt;

        private DebugFrame(
                WorldClient world,
                List<DebugLine> lines,
                String soundName,
                long expiresAt
        ) {
            this.world = world;
            this.lines = lines;
            this.soundName = soundName;
            this.expiresAt = expiresAt;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {

        if (!Config.debugSoundDirection) {
            FRAME.set(null);
            return;
        }

        DebugFrame frame = FRAME.get();

        if (frame == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.world == null || frame.world != mc.world) {
            FRAME.compareAndSet(frame, null);
            return;
        }

        if (System.currentTimeMillis() > frame.expiresAt) {
            FRAME.compareAndSet(frame, null);
            return;
        }

        Entity cameraEntity = mc.getRenderViewEntity();

        if (cameraEntity == null) {
            return;
        }

        Vec3d cameraPosition = cameraEntity.getPositionEyes(
                event.getPartialTicks()
        );

        double cameraX = cameraPosition.x;
        double cameraY = cameraPosition.y;
        double cameraZ = cameraPosition.z;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        GlStateManager.enableBlend();

        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ZERO
        );

        GlStateManager.depthMask(false);

        GL11.glLineWidth(2.0F);

        Tessellator tessellator =
                Tessellator.getInstance();

        BufferBuilder buffer =
                tessellator.getBuffer();

        buffer.begin(
                GL11.GL_LINES,
                DefaultVertexFormats.POSITION_COLOR
        );

        for (DebugLine line : frame.lines) {

            addVertex(
                    buffer,
                    line.from,
                    line.color,
                    cameraX,
                    cameraY,
                    cameraZ
            );

            addVertex(
                    buffer,
                    line.to,
                    line.color,
                    cameraX,
                    cameraY,
                    cameraZ
            );
        }

        tessellator.draw();

        GlStateManager.popMatrix();
        GL11.glPopAttrib();
    }

    private static void addVertex(
            BufferBuilder buffer,
            Vec3d position,
            int color,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        int alpha = (color >>> 24) & 255;
        int red = (color >>> 16) & 255;
        int green = (color >>> 8) & 255;
        int blue = color & 255;

        buffer.pos(
                position.x - cameraX,
                position.y - cameraY,
                position.z - cameraZ
        ).color(
                red,
                green,
                blue,
                alpha
        ).endVertex();
    }
}
