package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.Optional;
import java.util.OptionalDouble;

/** First world-space panel prototype; browser texture input follows in the next milestone. */
final class VisionWorldPanel {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("visionbrowser", "pipeline/world_panel"))
                    .build());
    private static final Vector4f COLOR = new Vector4f(1, 1, 1, 1);
    private static final Vector3f OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "Vision Browser World Panel", RenderType.SMALL_BUFFER_SIZE);
    private static PanelState state;

    private VisionWorldPanel() {}

    static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(VisionWorldPanel::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(VisionWorldPanel::draw);
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            state = null;
            return;
        }
        Vec3 look = client.player.getLookAngle();
        Vec3 center = client.player.getEyePosition().add(look.scale(2.6)).add(0, -0.08, 0);
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        if (right.lengthSqr() < 0.0001) right = new Vec3(1, 0, 0);
        Vec3 up = right.cross(look).normalize();
        double w = 1.05, h = 0.62;
        state = new PanelState(
                center.add(right.scale(-w)).add(up.scale(h)),
                center.add(right.scale(w)).add(up.scale(h)),
                center.add(right.scale(w)).add(up.scale(-h)),
                center.add(right.scale(-w)).add(up.scale(-h)));
    }

    private static void draw(LevelRenderContext context) {
        PanelState p = state;
        if (p == null) return;
        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology topology = PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology,
                topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
        PoseStack poseStack = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc matrix = poseStack.last().pose();
        VertexConsumer v = BUFFER.getVertexBuilder(draw);
        v.addVertex(matrix, (float)p.tl.x, (float)p.tl.y, (float)p.tl.z).setColor(0.38f, 0.62f, 0.82f, 0.34f);
        v.addVertex(matrix, (float)p.tr.x, (float)p.tr.y, (float)p.tr.z).setColor(0.48f, 0.72f, 0.92f, 0.34f);
        v.addVertex(matrix, (float)p.br.x, (float)p.br.y, (float)p.br.z).setColor(0.24f, 0.42f, 0.62f, 0.34f);
        v.addVertex(matrix, (float)p.bl.x, (float)p.bl.y, (float)p.bl.z).setColor(0.32f, 0.52f, 0.72f, 0.34f);
        poseStack.popPose();
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) execute(info);
        BUFFER.endFrame();
    }

    private static void execute(StagedVertexBuffer.ExecuteInfo info) {
        Minecraft client = Minecraft.getInstance();
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(), COLOR, OFFSET, TEXTURE);
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView color = target.getColorTextureView();
        if (color == null) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Vision Browser world panel", color, Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private record PanelState(Vec3 tl, Vec3 tr, Vec3 br, Vec3 bl) {}
}
