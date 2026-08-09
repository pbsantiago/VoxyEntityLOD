package io.github.turiom.voxyentitylod.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.turiom.voxyentitylod.client.render.RemoteEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
	// Hide the vanilla entity render once the mod copy takes over (>= 3rd chunk).
	// The mod copy itself goes through this same dispatcher — guard it by identity:
	// only the REAL entity (level.getEntity(id) == entity) can be cancelled.
	@Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true)
	private void voxyentitylod$hideVanilla(
			Entity entity, double x, double y, double z, float yRot, float tickDelta,
			PoseStack matrices, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		// Only hide when the mod copy exists (rule 3) and this is the real entity.
		if (RemoteEntityRenderer.get(entity.getId()) == null) return;
		if (mc.level.getEntity(entity.getId()) != entity) return;
		// Policy: vanilla renders chunks 1-2; from the 3rd chunk on the mod copy takes
		// over and the real must be hidden to avoid double rendering.
		if (entity.distanceToSqr(mc.player) <= 48.0 * 48.0) return;
		ci.cancel();
	}
}