package io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import io.github.turiom.voxyentitylod.networking.packet.LODEntityRenderingPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

public class LODEntityRenderingS2CEntityTickPacket implements LODEntityRenderingPacket {
	private static final @NotNull ResourceLocation ID = new ResourceLocation(VoxyEntityLOD.MOD_ID, "entity_tick");

	private final int entityId;
	private final @NotNull Vector3f entityPosition;

	public LODEntityRenderingS2CEntityTickPacket(int entityId, @NotNull Vector3f entityPosition) {
		this.entityId = entityId;
		this.entityPosition = entityPosition;
	}

	public LODEntityRenderingS2CEntityTickPacket(@NotNull FriendlyByteBuf buf) {
		this.entityId = buf.readInt();
		this.entityPosition = buf.readVector3f();
	}

	public static @NotNull ResourceLocation getId() { return ID; }

	@Override
	public @NotNull FriendlyByteBuf writeBuf() {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(entityId);
		buf.writeVector3f(entityPosition);
		return buf;
	}

	public int getEntityId() { return entityId; }
	public @NotNull Vector3f getEntityPosition() { return entityPosition; }
}
