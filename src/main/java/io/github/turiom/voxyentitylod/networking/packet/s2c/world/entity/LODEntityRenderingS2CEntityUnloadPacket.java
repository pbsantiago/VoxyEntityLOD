package io.github.turiom.voxyentitylod.networking.packet.s2c.world.entity;

import io.github.turiom.voxyentitylod.VoxyEntityLOD;
import io.github.turiom.voxyentitylod.networking.packet.LODEntityRenderingPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public class LODEntityRenderingS2CEntityUnloadPacket implements LODEntityRenderingPacket {
	private static final @NotNull ResourceLocation ID = new ResourceLocation(VoxyEntityLOD.MOD_ID, "entity_unload");

	private final int entityId;

	public LODEntityRenderingS2CEntityUnloadPacket(int entityId) { this.entityId = entityId; }

	public LODEntityRenderingS2CEntityUnloadPacket(@NotNull FriendlyByteBuf buf) { this.entityId = buf.readInt(); }

	public static @NotNull ResourceLocation getId() { return ID; }

	@Override
	public @NotNull FriendlyByteBuf writeBuf() {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(entityId);
		return buf;
	}

	public int getEntityId() { return entityId; }
}
