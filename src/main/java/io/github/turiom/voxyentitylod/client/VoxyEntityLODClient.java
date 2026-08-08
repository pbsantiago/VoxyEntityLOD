package io.github.turiom.voxyentitylod.client;

import io.github.turiom.voxyentitylod.client.render.ClientEntityBoxTracker;
import net.fabricmc.api.ClientModInitializer;

public class VoxyEntityLODClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientEntityBoxTracker.initialize();
	}
}
