package io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import io.github.turiom.voxyentitylod.networking.packet.LODEntityRenderingPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public class LODEntityRenderingS2CEntityLoadPacket implements LODEntityRenderingPacket {
	private static final @NotNull ResourceLocation ID = new ResourceLocation(VoxyEntityLOD.MOD_ID, "entity_load");

	private final int entityId;
	private final @NotNull ResourceLocation entityTextureId;
	private final @NotNull Vector3f entityPosition;
	private final @NotNull Vector3f entityBoundingBoxMin;
	private final @NotNull Vector3f entityBoundingBoxMax;
	private final @Nullable CompoundTag entityNbt;

	public LODEntityRenderingS2CEntityLoadPacket(
			int entityId, @NotNull ResourceLocation entityTextureId,
			@NotNull Vector3f entityPosition,
			@NotNull Vector3f entityBoundingBoxMin,
			@NotNull Vector3f entityBoundingBoxMax,
			@Nullable CompoundTag entityNbt
	) {
		this.entityId = entityId;
		this.entityTextureId = entityTextureId;
		this.entityPosition = entityPosition;
		this.entityBoundingBoxMin = entityBoundingBoxMin;
		this.entityBoundingBoxMax = entityBoundingBoxMax;
		this.entityNbt = entityNbt;
	}

	public LODEntityRenderingS2CEntityLoadPacket(@NotNull FriendlyByteBuf buf) {
		this.entityId = buf.readInt();
		this.entityTextureId = buf.readResourceLocation();
		this.entityPosition = buf.readVector3f();
		this.entityBoundingBoxMin = buf.readVector3f();
		this.entityBoundingBoxMax = buf.readVector3f();
		this.entityNbt = buf.readNbt();
	}

	public static @NotNull ResourceLocation getId() { return ID; }

	@Override
	public @NotNull FriendlyByteBuf writeBuf() {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(entityId);
		buf.writeResourceLocation(entityTextureId);
		buf.writeVector3f(entityPosition);
		buf.writeVector3f(entityBoundingBoxMin);
		buf.writeVector3f(entityBoundingBoxMax);
		buf.writeNbt(entityNbt);
		return buf;
	}

	public int getEntityId() { return entityId; }
	public @NotNull ResourceLocation getEntityTextureId() { return entityTextureId; }
	public @NotNull Vector3f getEntityPosition() { return entityPosition; }
	public @NotNull Vector3f getEntityBoundingBoxMin() { return entityBoundingBoxMin; }
	public @NotNull Vector3f getEntityBoundingBoxMax() { return entityBoundingBoxMax; }
	public @Nullable CompoundTag getEntityNbt() { return entityNbt; }
}
