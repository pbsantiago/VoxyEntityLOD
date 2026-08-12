package io.github.turiom.voxyentitylod.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.turiom.voxyentitylod.client.render.RemoteContraptionRenderer;
import io.github.turiom.voxyentitylod.client.render.RemoteEntityRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.ClientLevel;

@Environment(EnvType.CLIENT)
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private RenderBuffers renderBuffers;
	@Shadow @Nullable private ClientLevel level;

	@Unique private Frustum voxyentitylod$currentFrustum;
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void voxyentitylod$captureState(
			PoseStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline,
			Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
			Matrix4f projectionMatrix, CallbackInfo ci
	) {
		RemoteEntityRenderer.currentBufferSource = renderBuffers.bufferSource();
		RemoteEntityRenderer.currentCameraPos = camera.getPosition();
		RemoteEntityRenderer.currentTickDelta = tickDelta;
	}

	// O frustum REAL do vanilla chega como param em setupRender (mojang) / chamado todo frame
	// antes de renderizar. Capturar por injeção de param = bit-identico ao que o vanilla usa
	// para cullar entidades, sem @Shadow (campo real é cullingFrustum) e sem reconstruir.
	@Inject(method = "setupRender", at = @At("HEAD"))
	private void voxyentitylod$captureFrustum(
			Camera camera, Frustum frustum, boolean renderBlockOutline, boolean b2, CallbackInfo ci
	) {
		voxyentitylod$currentFrustum = frustum;
	}

	// 1.1 (shaders): render DENTRO da fase de entidades do renderLevel, não no TAIL.
	// Em 1.20.1 a fase é inline no renderLevel (INVOKE de ClientLevel.entitiesForRendering), entre
	// as layers sólidas/cutout e o translucent, com a projection do nível ainda ativa (o LevelRenderer
	// nunca troca a projection matrix dentro de renderLevel — zero setProjectionMatrix na classe).
	// No TAIL o draw caía fora dos gbuffers: o Iris finaliza em RETURN-shift-BEFORE = MESMO opcode do
	// TAIL, ordem indeterminada entre mixins → entidades/wool desenhados depois do finalize, sem
	// programa do pack, depth errado ("depende do pack"). Aqui os render types vanilla (entity_* dos
	// modelos, solid/cutout do wool e os chunkBufferLayers do Create) são mapeados pelos shaders do
	// pack para os gbuffers certos. endBatch() na hora evita o wool ficar pendurado até o flush final
	// (que sob Iris acontece pós-finalize).
	@Inject(method = "renderLevel",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
	private void voxyentitylod$renderRemoteEntities(
			PoseStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline,
			Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
			Matrix4f projectionMatrix, CallbackInfo ci
	) {
		if (level == null || minecraft.player == null) return;

		var bufferSource = renderBuffers.bufferSource();
		RemoteEntityRenderer.render(matrices, bufferSource, camera.getPosition(), tickDelta, voxyentitylod$currentFrustum);
		RemoteContraptionRenderer.render(matrices, bufferSource, camera.getPosition(), tickDelta, voxyentitylod$currentFrustum);
		bufferSource.endBatch();
	}
}
