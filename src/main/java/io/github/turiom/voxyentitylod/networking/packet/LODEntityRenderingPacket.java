package io.github.turiom.voxyentitylod.networking.packet;

import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public interface LODEntityRenderingPacket {
	@NotNull FriendlyByteBuf writeBuf();
}
