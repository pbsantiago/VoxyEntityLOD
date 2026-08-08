package io.github.turiom.voxyentitylod;

import io.github.turiom.voxyentitylod.server.VoxyEntityLODServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyEntityLOD implements ModInitializer {
	public static final String MOD_ID = "voxyentitylod";
	public static final String MOD_NAME = "Voxy Entity LOD";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Loading {}.", MOD_NAME);
		VoxyEntityLODServer.initialize();
	}
}
