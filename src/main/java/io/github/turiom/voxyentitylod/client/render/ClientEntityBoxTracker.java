package io.github.turiom.voxyentitylod.client.render;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODContraptionRenderingS2CContraptionLoadPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODContraptionRenderingS2CContraptionTickPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityLoadPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityTickPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityUnloadPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

@Environment(EnvType.CLIENT)
public class ClientEntityBoxTracker {
	public static void initialize() {
		// Limpa as cópias no disconnect: o contador de id é static (zera no restart do
		// servidor / troca de servidor), e ids podem colidir entre sessões — cópia órfã
		// da sessão anterior renderizaría no lugar da entidade nova.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			RemoteEntityRenderer.clear();
			RemoteContraptionRenderer.clear();
		});
		
		ClientPlayNetworking.registerGlobalReceiver(
				LODEntityRenderingS2CEntityLoadPacket.getId(),
				(client, handler, buf, sender) -> {
					var p = new LODEntityRenderingS2CEntityLoadPacket(buf);
					client.execute(() -> startTracking(p));
				});
		ClientPlayNetworking.registerGlobalReceiver(
				LODEntityRenderingS2CEntityUnloadPacket.getId(),
				(client, handler, buf, sender) -> {
					var p = new LODEntityRenderingS2CEntityUnloadPacket(buf);
					client.execute(() -> {
						RemoteEntityRenderer.remove(p.getEntityId());
						RemoteContraptionRenderer.removeContraption(p.getEntityId());
					});
				});
		ClientPlayNetworking.registerGlobalReceiver(
				LODEntityRenderingS2CEntityTickPacket.getId(),
				(client, handler, buf, sender) -> {
					var p = new LODEntityRenderingS2CEntityTickPacket(buf);
					client.execute(() -> RemoteEntityRenderer.updatePosition(
							p.getEntityId(), p.getEntityPosition(), p.getEntityYRot(), p.getEntityXRot()));
				});
		ClientPlayNetworking.registerGlobalReceiver(
				LODContraptionRenderingS2CContraptionLoadPacket.getId(),
				(client, handler, buf, sender) -> {
					var p = new LODContraptionRenderingS2CContraptionLoadPacket(buf);
					client.execute(() -> startTrackingContraption(p));
				});
		ClientPlayNetworking.registerGlobalReceiver(
				LODContraptionRenderingS2CContraptionTickPacket.getId(),
				(client, handler, buf, sender) -> {
					var p = new LODContraptionRenderingS2CContraptionTickPacket(buf);
					client.execute(() -> RemoteContraptionRenderer.updateContraption(
							p.getEntityId(), p.getEntityPosition(), p.getYaw(), p.getPitch()));
				});
	}

	private static void startTracking(LODEntityRenderingS2CEntityLoadPacket p) {
		var level = Minecraft.getInstance().level;
		if (level == null) return;

		var entityType = BuiltInRegistries.ENTITY_TYPE.get(p.getEntityTextureId());
		if (entityType == null) return;

		int id = p.getEntityId();
		// If already tracked, just update position — preserves instance.
		var existing = RemoteEntityRenderer.get(id);
		if (existing != null) {
			// Same id re-used by a different type after a despawn: drop the old copy, or
			// the dead entity keeps rendering in place of the new one.
			if (existing.getType() == entityType) {
				existing.setPos(p.getEntityPosition().x(), p.getEntityPosition().y(), p.getEntityPosition().z());
				return;
			}
			RemoteEntityRenderer.remove(id);
		}

		Entity entity;
		try {
			entity = entityType.create(level);
		} catch (Exception e) {
			VoxyEntityLOD.LOGGER.warn("Could not create remote entity of type {}", p.getEntityTextureId());
			return;
		}
		if (entity == null) return;

		entity.setId(id);
		entity.setPos(p.getEntityPosition().x(), p.getEntityPosition().y(), p.getEntityPosition().z());
		RemoteEntityRenderer.applyRotation(entity, p.getEntityYRot(), p.getEntityXRot());

		// renders black. Pos/UUID/Motion/Rotation already stripped server-side.
		var nbt = p.getEntityNbt();
		if (nbt != null) {
			try {
				entity.load(nbt);
			} catch (Exception ignored) {}
		}

		RemoteEntityRenderer.put(id, entity);
	}

	private static void startTrackingContraption(LODContraptionRenderingS2CContraptionLoadPacket p) {
		if (!RemoteContraptionRenderer.isCreateAvailable())
			return;

		var level = Minecraft.getInstance().level;
		if (level == null) return;

		var entityType = BuiltInRegistries.ENTITY_TYPE.get(p.getEntityTypeId());
		if (entityType == null) return;

		int id = p.getEntityId();

		// If already tracked, just update — preserves the rebuilt Contraption instance.
		var existing = RemoteContraptionRenderer.get(id);
		if (existing != null) {
			// Same id re-used by a different type after a despawn: stale copy must go.
			if (existing.getType() == entityType) {
				RemoteContraptionRenderer.updateContraption(id, p.getEntityPosition(), p.getYaw(), p.getPitch());
				return;
			}
			RemoteContraptionRenderer.removeContraption(id);
		}

		Entity entity;
		try {
			entity = entityType.create(level);
		} catch (Exception e) {
			VoxyEntityLOD.LOGGER.warn("Could not create remote contraption of type {}", p.getEntityTypeId());
			return;
		}
		if (entity == null) return;

		entity.setId(id);
		entity.setPos(p.getEntityPosition().x(), p.getEntityPosition().y(), p.getEntityPosition().z());

		// reconstructs the Contraption and wires contraption.entity = this (Create's
		// AbstractContraptionEntity.readAdditional does it for us).
		var nbt = p.getEntityNbt();
		if (nbt != null) {
			try {
				entity.load(nbt);
			} catch (Exception ignored) {}
		}

		RemoteContraptionRenderer.putContraption(id, entity, p.getYaw(), p.getPitch());
	}
}