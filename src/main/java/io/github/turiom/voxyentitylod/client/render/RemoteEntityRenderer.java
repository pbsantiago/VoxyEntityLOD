package io.github.turiom.voxyentitylod.client.render;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.config.VoxyConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public class RemoteEntityRenderer {
	// Fronteira vanilla ↔ mod: vanilla renderiza até 29 blocos (dist² < 900), o mod
	// assume a partir de 30. Um knob só — o gate da cópia e o mixin de cancelamento
	// leem a mesma constante pra nunca divergirem.
	public static final double TAKE_OVER_SQ = 30.0 * 30.0;
	private static final Map<Integer, Entity> ENTITIES = new ConcurrentHashMap<>();
	public static MultiBufferSource.BufferSource currentBufferSource;
	public static Vec3 currentCameraPos;
	public static float currentTickDelta;

	public static void put(int id, Entity entity) { ENTITIES.put(id, entity); }
	public static Entity get(int id) { return ENTITIES.get(id); }
	public static void remove(int id) { ENTITIES.remove(id); }
	public static void clear() { ENTITIES.clear(); }

	public static void updatePosition(int id, Vector3f pos, float yRot, float xRot) {
		var entity = ENTITIES.get(id);
		if (entity == null) return;
		entity.setPos(pos.x(), pos.y(), pos.z());
		applyRotation(entity, yRot, xRot);
	}

	// A LivingEntity's body/head orientation comes from yBodyRot/yHeadRot — the
	// vanilla tick keeps them in sync with yRot. A static copy never ticks, so we
	// set all three explicitly or every copy would face the same way (all zeros).
	// The renderer orients with lerp(yBodyRotO, yBodyRot, tick): the "previous"
	// fields must follow too, or the lerp swings the body between 0 and the target
	// every client tick (flickering between the old and the correct facing).
	public static void applyRotation(Entity entity, float yRot, float xRot) {
		entity.setYRot(yRot);
		entity.yRotO = yRot; // entity.yRotO / xRotO are fields in mojmap
		entity.setXRot(xRot);
		if (entity instanceof net.minecraft.world.entity.LivingEntity le) {
			le.setYHeadRot(yRot);
			le.yHeadRotO = yRot;
			le.setYBodyRot(yRot);
			le.yBodyRotO = yRot;
		}
	}

	private static int[] entityColor(Entity entity) {
		var path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		var c = COLORS.get(path);
		if (c!=null) return c;
		int hash=path.hashCode(); float hue=((hash&0xFFFF)/65536f)*360f;
		return hslToRgb(hue,0.7f,0.55f);
	}
	private static final java.util.Map<String,int[]> COLORS = new java.util.HashMap<>();
	static { put("sheep",230,230,230); put("cow",90,60,40); put("pig",230,180,180);
		put("chicken",240,230,210); put("rabbit",180,140,100); put("horse",120,80,40);
		put("zombie",74,130,60); put("skeleton",200,200,200); put("creeper",80,180,60);
		put("spider",80,70,70); put("enderman",20,20,20); put("witch",100,80,120);
		put("villager",140,100,60); put("iron_golem",157,157,151); put("snow_golem",240,240,250);
		put("blaze",240,200,40); put("ghast",200,200,210); put("slime",100,180,80);
		put("wolf",160,160,170); put("cat",220,180,120); put("fox",200,120,60);
		put("bee",220,180,40); put("goat",190,180,170); put("axolotl",230,140,180);
		put("allay",100,180,220); put("phantom",60,40,80); put("magma_cube",180,80,40); }
	private static void put(String p,int r,int g,int b) { COLORS.put(p,new int[]{r,g,b}); }

		// EntityCulling integration
	private static boolean ecLoaded;
	private static java.lang.reflect.Method ecMethod;
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

	private static int[] hslToRgb(float h,float s,float l) {
		float c=(1f-Math.abs(2f*l-1f))*s, x=c*(1f-Math.abs((h/60f)%2f-1f)), m=l-c/2f, r0,g0,b0;
		if (h<60f) { r0=c;g0=x;b0=0; } else if (h<120f) { r0=x;g0=c;b0=0; }
		else if (h<180f) { r0=0;g0=c;b0=x; } else if (h<240f) { r0=0;g0=x;b0=c; }
		else if (h<300f) { r0=x;g0=0;b0=c; } else { r0=c;g0=0;b0=x; }
		return new int[]{(int)((r0+m)*255),(int)((g0+m)*255),(int)((b0+m)*255)};
	}

	// LOD 1: wool block at entity's bounding box
	private static void drawLodBlock(Entity entity,PoseStack matrices,
			MultiBufferSource bufferSource,Vec3 cameraPos) {
		var state = dyeState(entityColor(entity));
		var bb=entity.getBoundingBox();
		float sx=(float)(bb.maxX-bb.minX), sy=(float)(bb.maxY-bb.minY), sz=(float)(bb.maxZ-bb.minZ);
		if (sx<0.01f||sy<0.01f||sz<0.01f) return;
		matrices.pushPose();
		matrices.translate(bb.minX-cameraPos.x,bb.minY-cameraPos.y,bb.minZ-cameraPos.z);
		matrices.scale(sx,sy,sz);
		Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
				state,matrices,bufferSource,15728880,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
		matrices.popPose();
	}

	private static final net.minecraft.world.level.block.Block[] WOOL = {
		net.minecraft.world.level.block.Blocks.WHITE_WOOL,net.minecraft.world.level.block.Blocks.ORANGE_WOOL,
		net.minecraft.world.level.block.Blocks.MAGENTA_WOOL,net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WOOL,
		net.minecraft.world.level.block.Blocks.YELLOW_WOOL,net.minecraft.world.level.block.Blocks.LIME_WOOL,
		net.minecraft.world.level.block.Blocks.PINK_WOOL,net.minecraft.world.level.block.Blocks.GRAY_WOOL,
		net.minecraft.world.level.block.Blocks.LIGHT_GRAY_WOOL,net.minecraft.world.level.block.Blocks.CYAN_WOOL,
		net.minecraft.world.level.block.Blocks.PURPLE_WOOL,net.minecraft.world.level.block.Blocks.BLUE_WOOL,
		net.minecraft.world.level.block.Blocks.BROWN_WOOL,net.minecraft.world.level.block.Blocks.GREEN_WOOL,
		net.minecraft.world.level.block.Blocks.RED_WOOL,net.minecraft.world.level.block.Blocks.BLACK_WOOL
	};
	private static net.minecraft.world.level.block.state.BlockState dyeState(int[] col) {
		int best=Integer.MAX_VALUE; net.minecraft.world.item.DyeColor bd=net.minecraft.world.item.DyeColor.WHITE;
		for (var d : net.minecraft.world.item.DyeColor.values()) {
			float[] f=d.getTextureDiffuseColors();
			int dr=(int)(f[0]*255), dg=(int)(f[1]*255), db=(int)(f[2]*255);
			int dist=(dr-col[0])*(dr-col[0])+(dg-col[1])*(dg-col[1])+(db-col[2])*(db-col[2]);
			if (dist<best) { best=dist; bd=d; }
		}
		return WOOL[bd.ordinal()].defaultBlockState();
	}

	private static boolean isUnderground(Entity entity, Minecraft mc) {
		var level = mc.level;
		if (level == null) return false;
		var pos = entity.blockPosition();
		return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY();
	}

	public static boolean superseded(Entity copy) {
		var lvl=Minecraft.getInstance().level;
		if (lvl==null) return false;
		var box=net.minecraft.world.phys.AABB.ofSize(copy.position(),6,6,6);
		for (var other : lvl.getEntitiesOfClass(Entity.class,box)) {
			if (other==copy||other.getId()==copy.getId()) continue;
			if (other.getType()==copy.getType()&&other.distanceToSqr(copy.position())<4) return true;
		}
		return false;
	}

	public static void render(PoseStack matrices,MultiBufferSource bufferSource,
			Vec3 cameraPos,float tickDelta,Frustum frustum) {
		if (ENTITIES.isEmpty()) return;
		var mc=Minecraft.getInstance(); var level=mc.level; var player=mc.player;
		if (level==null||player==null) return;

		double maxBlocks;
		try { maxBlocks=VoxyConfig.CONFIG.sectionRenderDistance*512; }
		catch (Exception|Error e) { return; }

		double lod1=maxBlocks*0.04;
		int cachedLight=net.minecraft.client.renderer.LightTexture.pack(15,15);

		RenderSystem.enableDepthTest(); RenderSystem.depthFunc(515);
		RenderSystem.depthMask(true); RenderSystem.enableCull();

		var dispatcher=mc.getEntityRenderDispatcher();
		dispatcher.setRenderShadow(false);

		for (var entry : ENTITIES.entrySet()) {
			var entity=entry.getValue();
			if (entity.isRemoved()) continue;
			boolean dbg = DEBUG && (++debugFrame & 19) == 0;

			double dist=entity.distanceTo(player);
			if (dist>maxBlocks) continue;

			// Frustum cull
			if (frustum!=null&&!frustum.isVisible(entity.getBoundingBoxForCulling())) continue;

			// Presence gate: vanilla renders up to 29 blocks; from 30 on the mod copies
			// take over — the copy renders and MixinEntityRenderDispatcher hides the vanilla.
			var real=level.getEntity(entity.getId());
			if (real!=null) {
				applyRotation(entity, real.getYRot(), real.getXRot());
				if (real.distanceToSqr(player) < TAKE_OVER_SQ
					&& frustum!=null && frustum.isVisible(real.getBoundingBoxForCulling())) continue;
			}

			// Expensive gates only for copies that will actually draw — the no-UNLOAD change
			// (1.0.18) keeps every copy alive forever, so cheap distance/frustum/reality checks
			// must run first or a traveled world pays full per-copy work for nothing.
			// (++frameCounter & 7): superseded scans a 6×6 box around every copy; 1/8 of frames ok.
			if ((++frameCounter & 7) == 0 && superseded(entity)) { ENTITIES.remove(entry.getKey()); continue; }

			// Skip underground entities (caves) — save GPU
			if (isUnderground(entity, mc)) continue;

			// EntityCulling: skip if the real entity is hidden behind solid geometry
			if (ecCulled(level.getEntity(entity.getId()))) continue;

			if (dbg && (entity.getId() % 17) == 0) {
				VoxyEntityLOD.LOGGER.info("LODRW id={} dist={} real={} yCopy={} yReal={} body={} head={}",
					entity.getId(), (int) dist, real != null,
					entity.getYRot(), real != null ? real.getYRot() : Float.NaN,
					entity instanceof net.minecraft.world.entity.LivingEntity le
						? le.yBodyRot : Float.NaN,
					entity instanceof net.minecraft.world.entity.LivingEntity le2
						? le2.yHeadRot : Float.NaN);
			}

			if (dist<=lod1) {
				double rx=entity.getX()-cameraPos.x, ry=entity.getY()-cameraPos.y, rz=entity.getZ()-cameraPos.z;
				matrices.pushPose();
				dispatcher.render(entity,rx,ry,rz,entity.getYRot(),tickDelta,matrices,bufferSource,cachedLight);
				matrices.popPose();
			} else {
				drawLodBlock(entity,matrices,bufferSource,cameraPos);
			}
		}
	}

	private static final boolean DEBUG = Boolean.getBoolean("voxyentitylod.debug");
	private static long debugFrame;
	private static int frameCounter;
}
