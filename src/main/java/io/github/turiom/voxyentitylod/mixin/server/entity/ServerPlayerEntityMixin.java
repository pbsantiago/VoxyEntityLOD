package io.github.turiom.voxyentitylod.mixin.server.entity;

import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityLoadPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityUnloadPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {
	private static final ResourceLocation FALLBACK_ID = new ResourceLocation("voxyentitylod", "fallback");

	@Unique private boolean voxyentitylod$wasSpectator;

	// EntityTrackingEvents doesn't fire for game mode changes, so we
	// manually send load/unload when toggling spectator.
	@Inject(method = "setGameMode", at = @At("HEAD"))
	private void voxyentitylod$capturePreviousState(GameType mode, CallbackInfoReturnable<GameType> cir) {
		var self = (ServerPlayer) (Object) this;
		this.voxyentitylod$wasSpectator = self.isSpectator();
	}

	@Inject(method = "setGameMode", at = @At("TAIL"))
	private void voxyentitylod$handleGameModeChange(GameType mode, CallbackInfoReturnable<GameType> cir) {
		var self = (ServerPlayer) (Object) this;
		var world = self.serverLevel();

		if (!voxyentitylod$wasSpectator && mode == GameType.SPECTATOR) {
			// Send unload to all other players
			var packet = new LODEntityRenderingS2CEntityUnloadPacket(self.getId()).writeBuf();
			for (var player : world.players()) {
				if (player == self || player.isSpectator()) continue;
				ServerPlayNetworking.send(player,
						LODEntityRenderingS2CEntityUnloadPacket.getId(), packet);
			}
		} else if (voxyentitylod$wasSpectator && mode != GameType.SPECTATOR) {
			// Send load to all other players
			var texId = BuiltInRegistries.ENTITY_TYPE.getKey(self.getType());
			if (texId == null) texId = FALLBACK_ID;
			var pos = self.position();
			var bb = self.getBoundingBox();
			var packet = new LODEntityRenderingS2CEntityLoadPacket(
					self.getId(), texId,
					new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
					new Vector3f((float) (bb.minX - pos.x), (float) (bb.minY - pos.y), (float) (bb.minZ - pos.z)),
					new Vector3f((float) (bb.maxX - pos.x), (float) (bb.maxY - pos.y), (float) (bb.maxZ - pos.z)),
					null,
					self.getYRot(), self.getXRot()
			).writeBuf();
			for (var player : world.players()) {
				if (player == self || player.isSpectator()) continue;
				ServerPlayNetworking.send(player,
						LODEntityRenderingS2CEntityLoadPacket.getId(), packet);
			}
		}
	}
}
