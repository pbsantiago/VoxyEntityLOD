package io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import io.github.turiom.voxyentitylod.networking.packet.LODEntityRenderingPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

public class LODContraptionRenderingS2CContraptionTickPacket implements LODEntityRenderingPacket {
	private static final @NotNull ResourceLocation ID = new ResourceLocation(VoxyEntityLOD.MOD_ID, "contraption_tick");

	private final int entityId;
	private final @NotNull Vector3f entityPosition;
	private final float yaw;
	private final float pitch;

	public LODContraptionRenderingS2CContraptionTickPacket(int entityId, @NotNull Vector3f entityPosition, float yaw, float pitch) {
		this.entityId = entityId;
		this.entityPosition = entityPosition;
		this.yaw = yaw;
		this.pitch = pitch;
	}

	public LODContraptionRenderingS2CContraptionTickPacket(@NotNull FriendlyByteBuf buf) {
		this.entityId = buf.readInt();
		this.entityPosition = buf.readVector3f();
		this.yaw = buf.readFloat();
		this.pitch = buf.readFloat();
	}

	public static @NotNull ResourceLocation getId() { return ID; }

	@Override
	public @NotNull FriendlyByteBuf writeBuf() {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(entityId);
		buf.writeVector3f(entityPosition);
		buf.writeFloat(yaw);
		buf.writeFloat(pitch);
		return buf;
	}

	public int getEntityId() { return entityId; }
	public @NotNull Vector3f getEntityPosition() { return entityPosition; }
	public float getYaw() { return yaw; }
	public float getPitch() { return pitch; }
}
