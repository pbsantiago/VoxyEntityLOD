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
		// Policy since 1.0.20: the remote copy only renders when the real entity is ABSENT
		// from the client (beyond vanilla tracking) — RemoteEntityRenderer skips every copy
		// whose real is present. So the vanilla render of the present real is always wanted
		// and never needs cancelling (cancelling it caused flicker: the copy draws only when
		// visible, but the real kept being hidden even when the copy wasn't drawn).
		// Kept as an inject no-op so reverting the policy is a one-liner if needed.
	}
}