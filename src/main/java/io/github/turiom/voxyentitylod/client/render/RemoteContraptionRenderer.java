package io.github.turiom.voxyentitylod.client.render;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import me.cortex.voxy.client.config.VoxyConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores remote contraption entities (outside vanilla tracking range) and renders them
 * using Create's own {@link ContraptionEntityRenderer} buffer pipeline, ignoring Flywheel
 * on purpose (a locally-synthesized entity has no Flywheel visual).
 */
@Environment(EnvType.CLIENT)
public final class RemoteContraptionRenderer {
	private static final Map<Integer, ContraptionState> CONTRAPTIONS = new ConcurrentHashMap<>();
	private static final Map<Integer, Boolean> prevVisMap = new ConcurrentHashMap<>();

	private static boolean createUnavailable;

	private RemoteContraptionRenderer() {
	}

	public static boolean isCreateAvailable() {
		return !createUnavailable && FabricLoader.getInstance().isModLoaded("create");
	}

	public static void putContraption(int id, Entity entity, float yaw, float pitch) {
		if (createUnavailable)
			return;
		if (!(entity instanceof AbstractContraptionEntity ace))
			return;
		ace.setYRot(yaw);
		ace.setXRot(pitch);
		int r=150,g=150,b=150;
		try {
			var blocks = ace.getContraption().getBlocks().values();
			var counts = new java.util.HashMap<Block, Integer>();
			for (var sbi : blocks) counts.merge(sbi.state().getBlock(), 1, Integer::sum);
			var top = counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
			int argb = top.defaultMapColor().col;
			r = (argb >> 16) & 0xFF; g = (argb >> 8) & 0xFF; b = argb & 0xFF;
		} catch (Exception ignored) {}
		CONTRAPTIONS.put(id, new ContraptionState(ace, r, g, b));
	}

	public static void updateContraption(int id, Vector3f pos, float yaw, float pitch) {
		var s = CONTRAPTIONS.get(id);
		if (s == null)
			return;
		// updates only, so the distant contraption follows location without rotating.
		s.entity().setPos(pos.x(), pos.y(), pos.z());
	}

	public static void removeContraption(int id) {
		CONTRAPTIONS.remove(id);
		prevVisMap.remove(id);
	}

	// null when unknown: lets callers inspect the copy (e.g. type check on id reuse).
	public static @Nullable AbstractContraptionEntity get(int id) {
		var s = CONTRAPTIONS.get(id);
		return s == null ? null : s.entity();
	}

	public static void render(PoseStack camera, MultiBufferSource.BufferSource buffers, Vec3 cameraPos, float tickDelta, Frustum frustum) {
		if (createUnavailable)
			return;

		var mc = Minecraft.getInstance();
		var level = mc.level;
		var player = mc.player;
		if (level == null || player == null)
			return;

		try {
			double maxBlocks;
			try { maxBlocks = VoxyConfig.CONFIG.sectionRenderDistance * 512; }
			catch (Exception | Error e) { return; }
			double lod1 = maxBlocks * 0.04;
			double maxSq = maxBlocks * maxBlocks;

			// Remote path — contraptions the server handed us (beyond its tracking range).
			for (var entry : CONTRAPTIONS.entrySet()) {
				int id = entry.getKey();
				var state = entry.getValue();
				var entity = state.entity();

				boolean nowVis = frustum != null && frustum.isVisible(entity.getBoundingBoxForCulling());
				Boolean prevVis = prevVisMap.get(id);
				if (DEBUG && (prevVis == null || prevVis != nowVis)) {
					VoxyEntityLOD.LOGGER.info("CCDBG id={} TRANSITION vis={} removed={} realPresent={} d2={} ecCulled={}, size={}",
						id, nowVis, entity.isRemoved(), level.getEntity(id) != null, (int) entity.distanceToSqr(player),
						ecCulled(level.getEntity(id)), CONTRAPTIONS.size());
				}
				if (DEBUG) prevVisMap.put(id, nowVis);

				if (entity.isRemoved()) {
					if (DEBUG) VoxyEntityLOD.LOGGER.warn("CCDBG id={} REMOVED — copy dropped permanently", id);
					CONTRAPTIONS.remove(id);
					prevVisMap.remove(id);
					continue;
				}

				// Manter = handover nos dois sentidos (inclusive retorno).
				if (level.getEntity(id) != null)
					continue;

				Contraption contraption = entity.getContraption();
				if (contraption == null)
					continue;

				double d2 = entity.distanceToSqr(player);
				if (d2 > maxSq) continue;
				if (RemoteEntityRenderer.superseded(entity)) {
					if (DEBUG) VoxyEntityLOD.LOGGER.warn("CCDBG id={} SUPERSEDED — copy dropped permanently", id);
					CONTRAPTIONS.remove(id);
					prevVisMap.remove(id);
					continue;
				}
				if (ecCulled(level.getEntity(entity.getId()))) {
					if (DEBUG) VoxyEntityLOD.LOGGER.info("CCDBG id={} ecCulled — skip this frame", id);
					continue;
				}
				if (frustum != null && !frustum.isVisible(entity.getBoundingBoxForCulling())) continue;
				double l1sq = lod1 * lod1;
				if (d2 <= l1sq) {
					renderOne(entity, contraption, camera, buffers, level, cameraPos, tickDelta);
				} else {
					drawContraptionBlock(entity, camera, buffers, cameraPos, state.r(), state.g(), state.b());
				}
			}

			// Live pass — closes the gap where some Create contraptions stop rendering
			// BEFORE the client's render distance while the server still tracks them.
			double liveBox = mc.options.getEffectiveRenderDistance() * 16 * 2;
			var box = AABB.ofSize(cameraPos, liveBox * 2, liveBox * 2, liveBox * 2);
			for (var e : level.getEntitiesOfClass(AbstractContraptionEntity.class, box)) {
				if (e.isRemoved())
					continue;
				var contraption = e.getContraption();
				if (contraption == null)
					continue;
				double d2 = e.distanceToSqr(player);
				if (ecCulled(level.getEntity(e.getId()))) continue;
				// vanilla ainda desenha (dentro do frustum): pula (não sobrepor); fora do
				// frustum o vanilla culla E a passada live assume — sem estado, per-frame,
				// então voltar a olhar restaura o desenho imediatamente.
				if (frustum != null && frustum.isVisible(e.getBoundingBoxForCulling()))
					continue;
				if (d2 > maxSq) continue;
				double l1sq = lod1 * lod1;
				int hc = e.getType().hashCode();
				int hcr = (hc>>16)&0xFF, hcg = (hc>>8)&0xFF, hcb = hc&0xFF;
				if (d2 <= l1sq) {
					renderOne(e, contraption, camera, buffers, level, cameraPos, tickDelta);
				} else {
					drawContraptionBlock(e, camera, buffers, cameraPos, hcr, hcg, hcb);
				}
			}
		} catch (LinkageError ex) {
			VoxyEntityLOD.LOGGER.error("CC kill-switch tripped: LinkageError in contraption render — all contraptions will stop rendering until relog", ex);
			createUnavailable = true;
			CONTRAPTIONS.clear();
			prevVisMap.clear();
		}
	}

	private static boolean ecLoaded;
	private static java.lang.reflect.Method ecMethod;
	private static final boolean DEBUG = Boolean.getBoolean("voxyentitylod.debug");
	static {
		try {
			var c = Class.forName("dev.tr7zw.entityculling.access.Cullable");
			ecMethod = c.getMethod("isCulled");
			ecLoaded = true;
		} catch (Exception ignored) {}
	}
	private static boolean ecCulled(Object e) {
		if (!ecLoaded || e == null) return false;
		try { return (boolean) ecMethod.invoke(e); } catch (Exception ex) { return false; }
	}

	private static void renderOne(
			AbstractContraptionEntity entity, Contraption contraption,
			PoseStack camera, MultiBufferSource.BufferSource buffers,
			net.minecraft.client.multiplayer.ClientLevel level, Vec3 cameraPos, float tickDelta
	) {
		ClientContraption cc = contraption.getOrCreateClientContraptionLazy();
		VirtualRenderWorld renderWorld = cc.getRenderLevel();

		// yaw/pitch frozen at load; partialTicks=1.0 reads the frozen values directly.
		float partialTicks = 1.0F;
		PoseStack model = new PoseStack();
		entity.applyLocalTransforms(model, partialTicks);
		Matrix4f light = new Matrix4f();
		ContraptionMatrices.translateToEntity(light, entity, partialTicks);

		camera.pushPose();
		camera.translate(entity.getX() - cameraPos.x, entity.getY() - cameraPos.y, entity.getZ() - cameraPos.z);

		for (RenderType renderType : RenderType.chunkBufferLayers()) {
			SuperByteBuffer sbb = ContraptionEntityRenderer.getBuffer(contraption, renderWorld, renderType);
			if (sbb.isEmpty())
				continue;
			VertexConsumer vc = buffers.getBuffer(renderType);
			sbb.transform(model)
				.useLevelLight(level, light)
				.renderInto(camera, vc);
		}

		camera.popPose();
	}

	private static void drawContraptionBlock(AbstractContraptionEntity entity, PoseStack camera,
			MultiBufferSource.BufferSource buffers, Vec3 cameraPos, int r, int g, int b) {
		var state = ccDye(r, g, b);
		var bb = entity.getBoundingBoxForCulling();
		float sx=(float)(bb.maxX-bb.minX), sy=(float)(bb.maxY-bb.minY), sz=(float)(bb.maxZ-bb.minZ);
		if (sx<0.01f||sy<0.01f||sz<0.01f) return;
		camera.pushPose();
		camera.translate(bb.minX-cameraPos.x, bb.minY-cameraPos.y, bb.minZ-cameraPos.z);
		camera.scale(sx, sy, sz);
		net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
				state, camera, buffers, 15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
		camera.popPose();
	}
	private static final net.minecraft.world.level.block.Block[] CCW = {
		net.minecraft.world.level.block.Blocks.WHITE_WOOL,net.minecraft.world.level.block.Blocks.ORANGE_WOOL,
		net.minecraft.world.level.block.Blocks.MAGENTA_WOOL,net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WOOL,
		net.minecraft.world.level.block.Blocks.YELLOW_WOOL,net.minecraft.world.level.block.Blocks.LIME_WOOL,
		net.minecraft.world.level.block.Blocks.PINK_WOOL,net.minecraft.world.level.block.Blocks.GRAY_WOOL,
		net.minecraft.world.level.block.Blocks.LIGHT_GRAY_WOOL,net.minecraft.world.level.block.Blocks.CYAN_WOOL,
		net.minecraft.world.level.block.Blocks.PURPLE_WOOL,net.minecraft.world.level.block.Blocks.BLUE_WOOL,
		net.minecraft.world.level.block.Blocks.BROWN_WOOL,net.minecraft.world.level.block.Blocks.GREEN_WOOL,
		net.minecraft.world.level.block.Blocks.RED_WOOL,net.minecraft.world.level.block.Blocks.BLACK_WOOL
	};
	private static net.minecraft.world.level.block.state.BlockState ccDye(int r, int g, int b) {
		int best=Integer.MAX_VALUE; net.minecraft.world.item.DyeColor bd=net.minecraft.world.item.DyeColor.WHITE;
		for (var d : net.minecraft.world.item.DyeColor.values()) {
			float[] f=d.getTextureDiffuseColors();
			int dr=(int)(f[0]*255), dg=(int)(f[1]*255), db=(int)(f[2]*255);
			int dist=(dr-r)*(dr-r)+(dg-g)*(dg-g)+(db-b)*(db-b);
			if (dist<best) { best=dist; bd=d; }
		}
		return CCW[bd.ordinal()].defaultBlockState();
	}

	private record ContraptionState(AbstractContraptionEntity entity, int r, int g, int b) {
	}
}