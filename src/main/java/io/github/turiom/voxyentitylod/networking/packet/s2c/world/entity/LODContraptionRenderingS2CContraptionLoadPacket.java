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

/**
 * Same shape as the entity load packet, but carries the full entity NBT (including the
 * "Contraption" structure) so the client can rebuild the contraption out of render distance.
 *           normal remote entities ignore rotation (see RemoteEntityRenderer).
 */
public class LODContraptionRenderingS2CContraptionLoadPacket implements LODEntityRenderingPacket {
	private static final @NotNull ResourceLocation ID = new ResourceLocation(VoxyEntityLOD.MOD_ID, "contraption_load");

	private final int entityId;
	private final @NotNull ResourceLocation entityTypeId;
	private final @NotNull Vector3f entityPosition;
	private final float yaw;
	private final float pitch;
	private final @Nullable CompoundTag entityNbt;

	public LODContraptionRenderingS2CContraptionLoadPacket(
			int entityId, @NotNull ResourceLocation entityTypeId,
			@NotNull Vector3f entityPosition, float yaw, float pitch,
			@Nullable CompoundTag entityNbt
	) {
		this.entityId = entityId;
		this.entityTypeId = entityTypeId;
		this.entityPosition = entityPosition;
		this.yaw = yaw;
		this.pitch = pitch;
		this.entityNbt = entityNbt;
	}

	public LODContraptionRenderingS2CContraptionLoadPacket(@NotNull FriendlyByteBuf buf) {
		this.entityId = buf.readInt();
		this.entityTypeId = buf.readResourceLocation();
		this.entityPosition = buf.readVector3f();
		this.yaw = buf.readFloat();
		this.pitch = buf.readFloat();
		this.entityNbt = buf.readNbt();
	}

	public static @NotNull ResourceLocation getId() { return ID; }

	@Override
	public @NotNull FriendlyByteBuf writeBuf() {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(entityId);
		buf.writeResourceLocation(entityTypeId);
		buf.writeVector3f(entityPosition);
		buf.writeFloat(yaw);
		buf.writeFloat(pitch);
		buf.writeNbt(entityNbt);
		return buf;
	}

	public int getEntityId() { return entityId; }
	public @NotNull ResourceLocation getEntityTypeId() { return entityTypeId; }
	public @NotNull Vector3f getEntityPosition() { return entityPosition; }
	public float getYaw() { return yaw; }
	public float getPitch() { return pitch; }
	public @Nullable CompoundTag getEntityNbt() { return entityNbt; }
}
