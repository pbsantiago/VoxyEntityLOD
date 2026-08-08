package io.github.turiom.voxyentitylod.server;

import io.github.turiom.voxyentitylod.server.entity.VoxyEntityLODServerEntityTracker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class VoxyEntityLODServer {
	private static VoxyEntityLODServerEntityTracker tracker;

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			int renderDistance = server.getPlayerList().getViewDistance();
			tracker = new VoxyEntityLODServerEntityTracker(renderDistance);
		});
	}
}
