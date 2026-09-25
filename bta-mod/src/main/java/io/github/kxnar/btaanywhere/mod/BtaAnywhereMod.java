package io.github.kxnar.btaanywhere.mod;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import io.github.kxnar.btaanywhere.mod.config.ConfigStore;
import io.github.kxnar.btaanywhere.mod.gui.HostingScreen;
import io.github.kxnar.btaanywhere.mod.gui.RecoveryScreen;
import io.github.kxnar.btaanywhere.mod.hosting.HostController;
import io.github.kxnar.btaanywhere.mod.hosting.WorldOpenGuard;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenConnectFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import turniplabs.halplibe.HalpLibe;
import turniplabs.halplibe.event.defs.CommonEvents;
import turniplabs.halplibe.util.dependency.Key;

public final class BtaAnywhereMod implements ModInitializer {
	public static final String MOD_ID = HalpLibe.registerMod("btaanywhere", true);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Object INITIALIZATION_LOCK = new Object();
	private static volatile HostController controller;
	private static volatile BtaAnywhereConfig config;
	private static final AtomicReference<BlockedOpenScreen> BLOCKED_OPEN_SCREEN = new AtomicReference<>();

	private enum BlockedOpenScreen { HOSTING, RECOVERY }

	@Override
	public void onInitialize() {
		CommonEvents.AFTER_GAME_START.listen(Key.of(MOD_ID), this::afterGameStart);
		LOGGER.info("BTA Anywhere initialized");
	}

	private void afterGameStart() {
		Minecraft minecraft = Minecraft.getMinecraft();
		initialize(minecraft);
		if (controller.hasRecoveryArtifacts()) {
			minecraft.displayScreen(new RecoveryScreen(minecraft.currentScreen));
		}
	}

	public static HostController controller(Minecraft minecraft) {
		initialize(minecraft);
		return controller;
	}

	public static BtaAnywhereConfig config(Minecraft minecraft) {
		initialize(minecraft);
		return config;
	}

	public static void openHostingScreen(Minecraft minecraft, Screen parent) {
		initialize(minecraft);
		minecraft.displayScreen(new HostingScreen(parent));
	}

	public static void clientTick(Minecraft minecraft) {
		BlockedOpenScreen blocked = BLOCKED_OPEN_SCREEN.getAndSet(null);
		if (blocked != null) {
			minecraft.displayScreen(blocked == BlockedOpenScreen.RECOVERY
				? new RecoveryScreen(minecraft.currentScreen)
				: new HostingScreen(minecraft.currentScreen));
			return;
		}
		HostController active = controller;
		if (active == null) {
			return;
		}
		boolean multiplayerPresent = minecraft.currentWorld != null && minecraft.isMultiplayerWorld();
		active.observeConnectedWorld(multiplayerPresent, minecraft.currentScreen instanceof ScreenConnectFailed);
	}

	public static boolean blockSinglePlayerWorldOpen(Minecraft minecraft, String worldDirectoryName) {
		HostController active = controller(minecraft);
		var gameDirectory = minecraft.getMinecraftDir().toPath();
		var inMemoryLiveWorld = active.liveOriginalNeedingGuard();
		if (inMemoryLiveWorld.isPresent()
			&& WorldOpenGuard.blocksLiveWorld(gameDirectory, worldDirectoryName, inMemoryLiveWorld.get())) {
			BLOCKED_OPEN_SCREEN.set(active.hasRecoveryArtifacts()
				? BlockedOpenScreen.RECOVERY : BlockedOpenScreen.HOSTING);
			return true;
		}
		if (WorldOpenGuard.blocks(gameDirectory, worldDirectoryName)) {
			BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.RECOVERY);
			return true;
		}
		return false;
	}

	public static boolean blockNewSinglePlayerWorld(Minecraft minecraft) {
		if (!WorldOpenGuard.blocksNewWorld(minecraft.getMinecraftDir().toPath())) {
			return false;
		}
		BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.RECOVERY);
		return true;
	}

	private static void initialize(Minecraft minecraft) {
		if (controller != null) {
			return;
		}
		synchronized (INITIALIZATION_LOCK) {
			if (controller != null) {
				return;
			}
			ConfigStore store = new ConfigStore(minecraft.getMinecraftDir().toPath());
			try {
				config = store.load();
			} catch (Exception exception) {
				LOGGER.error("Could not load BTA Anywhere configuration; using an in-memory default", exception);
				config = new BtaAnywhereConfig();
			}
			controller = new HostController(minecraft.getMinecraftDir().toPath());
			HostController shutdownController = controller;
			Runtime.getRuntime().addShutdownHook(new Thread(shutdownController::close, "bta-anywhere-shutdown"));
		}
	}
}
