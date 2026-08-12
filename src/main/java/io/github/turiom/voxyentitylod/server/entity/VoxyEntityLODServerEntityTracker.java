package io.github.turiom.voxyentitylod.server.entity;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODContraptionRenderingS2CContraptionLoadPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODContraptionRenderingS2CContraptionTickPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityLoadPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityTickPacket;
import io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity.LODEntityRenderingS2CEntityUnloadPacket;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import java.util.*;

// Low-frequency TICK (every 100 ticks) so far-away entities update position.
public class VoxyEntityLODServerEntityTracker {
	private static final @NotNull ResourceLocation FALLBACK_ID = new ResourceLocation(VoxyEntityLOD.MOD_ID, "fallback");
	private static final int TICK_INTERVAL = 100; // ticks (~5s)
	// is still ~2 chunks inside vanilla tracking so the client builds the copy with lead.
	private static final int PREFETCH_INTERVAL = 10; // ticks (~0.5s)

	private int rangeBlocks;
	private double rangeSq;
	private final Map<UUID, Set<Integer>> playerTracked = new HashMap<>();
	// set so unload/tick routing is explicit. Same lifecycle, simpler diff.
	private final Map<UUID, Set<Integer>> contraptionTracked = new HashMap<>();
	// Contraptions the server pre-sent while vanilla still tracks them (load packet sent
	// early so the client builds the structure BEFORE the handover — no pop-in delay).
	// Promoted to contraptionTracked on STOP_TRACKING with a fresh Tick (no NBT resend).
	private final Map<UUID, Set<Integer>> prefetchSet = new HashMap<>();
	// Entities vanilla is currently tracking (within vanilla render distance).
	// The mod must NEVER touch these — vanilla already renders them.
	private final Map<UUID, Set<Integer>> vanillaTracked = new HashMap<>();
	// Last position the server saw each tracked entity — lets the sweep tell apart
	// "chunk unloaded (entity frozen, copy must stay)" from "entity died/despawned in
	// a loaded chunk (copy must die)".
	private final Map<Integer, BlockPos> lastSeenPos = new HashMap<>();
	private int tickCounter;
	// Guarded once so a dedicated server without Create never trips NoClassDefFoundError.
	private final boolean createLoaded = FabricLoader.getInstance().isModLoaded("create");

	public VoxyEntityLODServerEntityTracker(int vanillaRenderDistance) {
		int chunks = Math.max(vanillaRenderDistance, 2);
		this.rangeBlocks = Math.min(Math.max(chunks * 16 * 4, 512), 2048);
		this.rangeSq = (double) rangeBlocks * rangeBlocks;
		VoxyEntityLOD.LOGGER.info("Entity tracking range: {} blocks ({}x vanilla render distance)", rangeBlocks, rangeBlocks/(chunks*16));

		// Entity enters vanilla range → mod stops
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			vanillaTracked.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
			// re-entry into vanilla range: forget the copy WITHOUT sending UNLOAD — the client
			// drops it lazily via the render gate (real present + frustum-visible). Sending it
			// early is what painted the 1-chunk gap when moving back.
			if (isTracked(player, entity)) forgetTracking(player, entity);
		});

		// Entity leaves vanilla range → mod takes over
		EntityTrackingEvents.STOP_TRACKING.register((entity, player) -> {
			var vset = vanillaTracked.get(player.getUUID());
			if (vset != null) vset.remove(entity.getId());
			if (entity.level() != player.level()) return;
			// Death/despawn can race the handover: never (re)start tracking a corpse,
			// or the client copy resurrects as a frozen ghost.
			if (entity.isRemoved()) return;
			if (!inRange(entity, player)) return;
			// prefetch → promote: copy already built client-side, just refresh position
			// (a full NBT resend would rebuild the contraption and reintroduce the delay).
			var pset = prefetchSet.get(player.getUUID());
			if (pset != null && pset.remove(entity.getId())) {
				// promote: copy already built client-side, just refresh position (a full NBT
				// resend would rebuild the contraption and reintroduce the delay).
				if (entity instanceof AbstractContraptionEntity) {
					contraptionTracked.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
					sendContraptionTick(player, entity);
				} else {
					playerTracked.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
					sendEntityTick(player, entity);
				}
				return;
			}
			startTracking(player, entity);
		});

		// ENTITY_UNLOAD fires from the vanilla tracking handler on BOTH death and chunk
		// unload — the removal reason tells them apart. reason == null (entity merely left
		// everyone's vanilla tracking range, still alive) and UNLOADED_TO_CHUNK are the
		// "border" cases the copy exists for: those MUST NOT send UNLOAD (that was the old
		// "some quando troca de chunk" bug). KILLED / DISCARDED / dimension change → real
		// removal → kill the copy. Only fires for entities the vanilla tracker knew (a few
		// chunks); deaths beyond that never event — the sweep (prune) is the backstop.
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			int id = entity.getId();
			var reason = entity.getRemovalReason();
			// UNLOADED_TO_CHUNK: entidade congelada no disco, a cópia deve ficar.
			// O prune re-checa quando o chunk recarregar — se o mob não voltar (ou
			// voltar com id novo), o prune manda UNLOAD. Não mexer nos sets.
			if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) return;
			if (reason != null)
				for (var player : world.players())
					if (isTracked(player, entity)) sendUnload(player, id);

			for (var uuid : playerTracked.keySet())
				if (playerTracked.get(uuid) != null) playerTracked.get(uuid).remove(id);
			for (var uuid : contraptionTracked.keySet())
				if (contraptionTracked.get(uuid) != null) contraptionTracked.get(uuid).remove(id);
			for (var uuid : prefetchSet.keySet())
				if (prefetchSet.get(uuid) != null) prefetchSet.get(uuid).remove(id);
			lastSeenPos.remove(id);
		});

		// Player joins → send all entities
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			sendAllInRange(handler.getPlayer()));

		// Player disconnects → drop all state (would otherwise leak per-player sets)
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			clearPlayer(handler.getPlayer()));

		// Player changes dimension → limpa as cópias do cliente e re-envia
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			unloadAllAndClear(player);
			sendAllInRange(player);
		});

		// that were loaded from disk (e.g. chunk reload with new entity ID).
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;
			if (tickCounter % 20 == 0) updateRange(server);

			// sends the Load packet while the server still tracks the entity/contraption (a
			// couple chunks inside its range) so the client builds the copy with lead time.
			// Handover at STOP_TRACKING then costs a Tick only, no pop-in.
			if (tickCounter % PREFETCH_INTERVAL == 0) {
				for (var world : server.getAllLevels()) {
					for (var player : world.players()) {
						pruneTracked(player, world);
						var playerPos = player.position();
						var box = AABB.ofSize(playerPos, rangeBlocks * 2, rangeBlocks * 2, rangeBlocks * 2);
						for (var entity : world.getEntitiesOfClass(Entity.class, box)) {
							if (entity == player || entity instanceof ServerPlayer sp && sp.isSpectator())
								continue;
							if (isTracked(player, entity) && !inVanilla(player, entity)) continue;
							double d2 = entity.distanceToSqr(player);
							if (d2 > rangeSq) continue;
							if (createLoaded && entity instanceof AbstractContraptionEntity ace)
								startPrefetchContraption(player, ace);
							else
								startPrefetchEntity(player, entity);
						}
					}
				}
			}

			if (tickCounter % TICK_INTERVAL != 0) return;
			for (var world : server.getAllLevels()) {
				for (var player : world.players()) {
					var playerPos = player.position();
					var tracked = playerTracked.get(player.getUUID());
					var contraptions = contraptionTracked.get(player.getUUID());
					var prefetched = prefetchSet.get(player.getUUID());

					// Send position TICK for already-tracked entities, plus prefetched
					// ones (their copies exist on the client and must keep orientation).
					if (tracked != null || prefetched != null) {
						var ids = new java.util.HashSet<Integer>();
						if (tracked != null) ids.addAll(tracked);
						if (prefetched != null) ids.addAll(prefetched);
						for (int entityId : ids) {
							var entity = world.getEntity(entityId);
							if (entity == null) continue;
							var pos = entity.position();
							lastSeenPos.put(entityId, BlockPos.containing(pos.x, pos.y, pos.z));
							ServerPlayNetworking.send(player, LODEntityRenderingS2CEntityTickPacket.getId(),
									new LODEntityRenderingS2CEntityTickPacket(
											entityId, new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
											entity.getYRot(), entity.getXRot()).writeBuf());
						}
					}

					// contraptions need yaw/pitch too, so a separate (smaller traffic) tick
					if (contraptions != null) {
						for (int entityId : contraptions) {
							var entity = world.getEntity(entityId);
							if (entity == null) continue;
							var pos = entity.position();
							lastSeenPos.put(entityId, BlockPos.containing(pos.x, pos.y, pos.z));
							ServerPlayNetworking.send(player, LODContraptionRenderingS2CContraptionTickPacket.getId(),
									new LODContraptionRenderingS2CContraptionTickPacket(
											entityId,
											new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
											entity.getYRot(), entity.getXRot()).writeBuf());
						}
					}

					// Scan for NEW entities (loaded from disk, new spawns beyond vanilla range)
					var box = AABB.ofSize(playerPos, rangeBlocks * 2, rangeBlocks * 2, rangeBlocks * 2);
					var vset = vanillaTracked.get(player.getUUID());
					for (var entity : world.getEntitiesOfClass(Entity.class, box)) {
						if (entity == player) continue;
						if (entity instanceof ServerPlayer sp && sp.isSpectator()) continue;
						if (isTracked(player, entity)) continue; // já trackeado
						if (vset != null && vset.contains(entity.getId())) continue; // vanilla já renderiza
						if (inRange(entity, player)) startTracking(player, entity);
					}
				}
			}
		});
	}

	private void pruneTracked(@NotNull ServerPlayer player, @NotNull ServerLevel world) {
		prune(world, player, playerTracked.get(player.getUUID()));
		prune(world, player, contraptionTracked.get(player.getUUID()));
		prune(world, player, prefetchSet.get(player.getUUID()));
	}

	// Death beyond vanilla tracking: ENTITY_UNLOAD never fires there (the vanilla tracker
	// never knew the entity), so the sweep is the only witness. The catch: "server entity
	// gone" also happens on chunk unload (entity frozen in an unloaded chunk, copy must
	// stay). An entity can only die/despawn while its chunk is loaded, so a still-loaded
	// chunk at the last known position means real death → UNLOAD; unloaded chunk → border
	// case → keep the copy.
	private void prune(@NotNull ServerLevel world, @NotNull ServerPlayer player, @Nullable Set<Integer> set) {
		if (set == null) return;
		for (var it = set.iterator(); it.hasNext();) {
			int id = it.next();
			if (world.getEntity(id) != null) continue;
			// Chunk descarregado = entidade congelada no disco, cópia fica. NÃO dropar o id:
			// quando o chunk recarregar, o mob ou despawna ou volta com ID NOVO —
			// world.getEntity(idVelho) fica null pra sempre, e só re-checar o id no set
			// manda o UNLOAD que o ghost precisa. (Chunk off = re-check no próximo sweep.)
			var last = lastSeenPos.get(id);
			if (last == null || !world.isLoaded(last)) continue;
			it.remove();
			sendUnload(player, id);
		}
	}

	private void updateRange(net.minecraft.server.MinecraftServer server) {
		int chunks = Math.max(server.getPlayerList().getViewDistance(), 2);
		this.rangeBlocks = Math.min(Math.max(chunks * 16 * 4, 512), 2048);
		this.rangeSq = (double) rangeBlocks * rangeBlocks;
	}

	private void sendAllInRange(ServerPlayer player) {
		var world = player.serverLevel();
		var pos = player.position();
		var box = AABB.ofSize(pos, rangeBlocks * 2, rangeBlocks * 2, rangeBlocks * 2);
		var vset = vanillaTracked.get(player.getUUID());
		for (var entity : world.getEntitiesOfClass(Entity.class, box)) {
			if (entity == player) continue;
			if (entity instanceof ServerPlayer sp && sp.isSpectator()) continue;
			// vanilla already renders these — the mod only exists beyond render distance
			if (vset != null && vset.contains(entity.getId())) continue;
			if (inRange(entity, player)) startTracking(player, entity);
		}
	}

	private boolean inRange(Entity entity, ServerPlayer player) {
		return entity.distanceToSqr(player) <= rangeSq;
	}

	private boolean isTracked(ServerPlayer player, Entity entity) {
		var set = playerTracked.get(player.getUUID());
		if (set != null && set.contains(entity.getId())) return true;
		var cset = contraptionTracked.get(player.getUUID());
		if (cset != null && cset.contains(entity.getId())) return true;
		var pset = prefetchSet.get(player.getUUID());
		return pset != null && pset.contains(entity.getId());
	}

	private boolean inVanilla(ServerPlayer player, Entity entity) {
		var vset = vanillaTracked.get(player.getUUID());
		return vset != null && vset.contains(entity.getId());
	}

	private void clearPlayer(ServerPlayer player) {
		playerTracked.remove(player.getUUID());
		contraptionTracked.remove(player.getUUID());
		prefetchSet.remove(player.getUUID());
		vanillaTracked.remove(player.getUUID());
	}

	private void unloadAllAndClear(ServerPlayer player) {
		var uuid = player.getUUID();
		unloadSet(player, playerTracked.get(uuid));
		unloadSet(player, contraptionTracked.get(uuid));
		unloadSet(player, prefetchSet.get(uuid));
		clearPlayer(player);
	}

	private void unloadSet(ServerPlayer player, @Nullable Set<Integer> set) {
		if (set == null) return;
		for (int id : set) sendUnload(player, id);
	}

	private void startTracking(@NotNull ServerPlayer player, @NotNull Entity entity) {
		if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) return; // dropped items & XP orbs: vanilla range is enough, LOD copy looks wrong
		// rebuild them out of render distance. Only handles loaded Create contraptions.
		if (createLoaded && entity instanceof AbstractContraptionEntity) {
			startTrackingContraption(player, (AbstractContraptionEntity) entity);
			return;
		}

		sendEntityLoad(player, entity);
		playerTracked.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
	}

	private void startTrackingContraption(@NotNull ServerPlayer player, @NotNull AbstractContraptionEntity entity) {
		sendContraptionLoad(player, entity);
		contraptionTracked.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
	}

	private void startPrefetchContraption(@NotNull ServerPlayer player, @NotNull AbstractContraptionEntity entity) {
		// Same full-NBT Load as startTrackingContraption, but the copy is built while
		// vanilla still renders the real one — promotion on STOP_TRACKING costs a Tick only.
		sendContraptionLoad(player, entity);
		prefetchSet.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
	}

	private void startPrefetchEntity(@NotNull ServerPlayer player, @NotNull Entity entity) {
		if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) return; // keep dropped items & XP orbs out of the LOD pipeline
		sendEntityLoad(player, entity);
		prefetchSet.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(entity.getId());
	}

	private void sendEntityLoad(@NotNull ServerPlayer player, @NotNull Entity entity) {
		@Nullable var texId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		if (texId == null) texId = FALLBACK_ID;

		var pos = entity.position();
		// grava a posição no Load: o sweep precisa do lastSeenPos pra decidir, e a
		// entidade pode morrer antes do primeiro refresh de 100 ticks (last == null = ghost).
		lastSeenPos.put(entity.getId(), BlockPos.containing(pos.x, pos.y, pos.z));
		var bb = entity.getBoundingBox();

		// renders the correct appearance (e.g. black sheep, brown llama).
		@Nullable CompoundTag nbt = null;
		try {
			var tag = new CompoundTag();
			entity.saveWithoutId(tag);
			tag.remove("Pos");
			tag.remove("UUID");
			tag.remove("Motion");
			tag.remove("Rotation");
			if (!tag.isEmpty()) nbt = tag;
		} catch (Exception ignored) {}

		ServerPlayNetworking.send(player, LODEntityRenderingS2CEntityLoadPacket.getId(),
				new LODEntityRenderingS2CEntityLoadPacket(
						entity.getId(), texId,
						new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
						new Vector3f((float) (bb.minX - pos.x), (float) (bb.minY - pos.y), (float) (bb.minZ - pos.z)),
						new Vector3f((float) (bb.maxX - pos.x), (float) (bb.maxY - pos.y), (float) (bb.maxZ - pos.z)),
						nbt,
						entity.getYRot(), entity.getXRot()
				).writeBuf());
	}

	private void sendEntityTick(@NotNull ServerPlayer player, @NotNull Entity entity) {
		var pos = entity.position();
		ServerPlayNetworking.send(player, LODEntityRenderingS2CEntityTickPacket.getId(),
				new LODEntityRenderingS2CEntityTickPacket(
						entity.getId(), new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
						entity.getYRot(), entity.getXRot()).writeBuf());
	}

	private void sendContraptionLoad(@NotNull ServerPlayer player, @NotNull AbstractContraptionEntity entity) {
		@Nullable var texId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		if (texId == null) texId = FALLBACK_ID;

		var pos = entity.position();
		lastSeenPos.put(entity.getId(), BlockPos.containing(pos.x, pos.y, pos.z));

		// strip only per-motion/render-irrelevant fields; yaw/pitch travel in the packet.
		@Nullable CompoundTag nbt = null;
		try {
			var tag = new CompoundTag();
			entity.saveWithoutId(tag);
			tag.remove("Pos");
			tag.remove("UUID");
			tag.remove("Motion");
			tag.remove("Rotation");
			if (!tag.isEmpty()) nbt = tag;
		} catch (Exception ignored) {}

		ServerPlayNetworking.send(player, LODContraptionRenderingS2CContraptionLoadPacket.getId(),
				new LODContraptionRenderingS2CContraptionLoadPacket(
						entity.getId(), texId,
						new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
						entity.getYRot(), entity.getXRot(),
						nbt
				).writeBuf());
	}

	private void sendUnload(@NotNull ServerPlayer player, int entityId) {
		ServerPlayNetworking.send(player, LODEntityRenderingS2CEntityUnloadPacket.getId(),
				new LODEntityRenderingS2CEntityUnloadPacket(entityId).writeBuf());
	}

	private void sendContraptionTick(@NotNull ServerPlayer player, @NotNull Entity entity) {
		var pos = entity.position();
		ServerPlayNetworking.send(player, LODContraptionRenderingS2CContraptionTickPacket.getId(),
				new LODContraptionRenderingS2CContraptionTickPacket(
						entity.getId(),
						new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
						entity.getYRot(), entity.getXRot()).writeBuf());
	}

	private void forgetTracking(@NotNull ServerPlayer player, @NotNull Entity entity) {
		var set = playerTracked.get(player.getUUID());
		if (set != null) set.remove(entity.getId());
		var contraptions = contraptionTracked.get(player.getUUID());
		if (contraptions != null) contraptions.remove(entity.getId());
		var prefetched = prefetchSet.get(player.getUUID());
		if (prefetched != null) prefetched.remove(entity.getId());
	}
}