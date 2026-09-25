package io.github.kxnar.btaanywhere.mod;

import io.github.kxnar.btaanywhere.mod.config.BtaAnywhereConfig;
import io.github.kxnar.btaanywhere.mod.config.ConfigStore;
import io.github.kxnar.btaanywhere.mod.gui.HostingScreen;
import io.github.kxnar.btaanywhere.mod.gui.GameDirectoryInUseScreen;
import io.github.kxnar.btaanywhere.mod.gui.RecoveryScreen;
import io.github.kxnar.btaanywhere.mod.hosting.GameDirectoryLease;
import io.github.kxnar.btaanywhere.mod.hosting.HostController;
import io.github.kxnar.btaanywhere.mod.hosting.WorldOpenGuard;
import java.io.IOException;
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
	private static volatile String gameDirectoryFailure;
	private static volatile BtaAnywhereConfig config;
	private static final AtomicReference<BlockedOpenScreen> BLOCKED_OPEN_SCREEN = new AtomicReference<>();

	private enum BlockedOpenScreen { HOSTING, RECOVERY, GAME_DIRECTORY }

	@Override
	public void onInitialize() {
		CommonEvents.AFTER_GAME_START.listen(Key.of(MOD_ID), this::afterGameStart);
		LOGGER.info("BTA Anywhere initialized");
	}

	private void afterGameStart() {
		Minecraft minecraft = Minecraft.getMinecraft();
		initialize(minecraft);
		if (usableController() == null) {
			minecraft.displayScreen(new GameDirectoryInUseScreen(minecraft.currentScreen,
				directoryFailureMessage()));
			return;
		}
		if (controller.hasRecoveryArtifacts()) {
			minecraft.displayScreen(new RecoveryScreen(minecraft.currentScreen));
		}
	}

	public static HostController controller(Minecraft minecraft) {
		HostController active = activeController(minecraft);
		if (active == null) {
			throw new IllegalStateException("BTA Anywhere cannot use this game directory: "
				+ directoryFailureMessage());
		}
		return active;
	}

	/** Returns null when startup failed or a previously opened controller was closed. */
	public static HostController activeController(Minecraft minecraft) {
		initialize(minecraft);
		return usableController();
	}

	public static void showGameDirectoryFailure(Minecraft minecraft, Screen parent) {
		minecraft.displayScreen(new GameDirectoryInUseScreen(parent, directoryFailureMessage()));
	}

	public static boolean hasRecoveryArtifacts(Minecraft minecraft) {
		initialize(minecraft);
		HostController active = usableController();
		return active != null && active.hasRecoveryArtifacts();
	}

	public static BtaAnywhereConfig config(Minecraft minecraft) {
		initialize(minecraft);
		return config;
	}

	public static void openHostingScreen(Minecraft minecraft, Screen parent) {
		initialize(minecraft);
		minecraft.displayScreen(usableController() == null
			? new GameDirectoryInUseScreen(parent, directoryFailureMessage())
			: new HostingScreen(parent));
	}

	public static void openRecoveryScreen(Minecraft minecraft, Screen parent) {
		initialize(minecraft);
		minecraft.displayScreen(usableController() == null
			? new GameDirectoryInUseScreen(parent, directoryFailureMessage())
			: new RecoveryScreen(parent));
	}

	public static void clientTick(Minecraft minecraft) {
		BlockedOpenScreen blocked = BLOCKED_OPEN_SCREEN.getAndSet(null);
		if (blocked != null) {
			if (usableController() == null) {
				minecraft.displayScreen(new GameDirectoryInUseScreen(minecraft.currentScreen,
					directoryFailureMessage()));
				return;
			}
			minecraft.displayScreen(switch (blocked) {
				case RECOVERY -> new RecoveryScreen(minecraft.currentScreen);
				case HOSTING -> new HostingScreen(minecraft.currentScreen);
				case GAME_DIRECTORY -> new GameDirectoryInUseScreen(minecraft.currentScreen,
					directoryFailureMessage());
			});
			return;
		}
		HostController active = usableController();
		if (active == null) {
			return;
		}
		boolean multiplayerPresent = minecraft.currentWorld != null && minecraft.isMultiplayerWorld();
		active.observeConnectedWorld(multiplayerPresent, minecraft.currentScreen instanceof ScreenConnectFailed);
	}

	public static boolean blockSinglePlayerWorldOpen(Minecraft minecraft, String worldDirectoryName) {
		initialize(minecraft);
		HostController active = usableController();
		if (active == null) {
			BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.GAME_DIRECTORY);
			return true;
		}
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
		initialize(minecraft);
		if (usableController() == null) {
			BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.GAME_DIRECTORY);
			return true;
		}
		if (!WorldOpenGuard.blocksNewWorld(minecraft.getMinecraftDir().toPath())) {
			return false;
		}
		BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.RECOVERY);
		return true;
	}

	private static HostController usableController() {
		HostController active = controller;
		return active != null && active.hasActiveGameDirectoryLease() ? active : null;
	}

	private static String directoryFailureMessage() {
		return gameDirectoryFailure == null
			? GameDirectoryLease.UNAVAILABLE_MESSAGE : gameDirectoryFailure;
	}

	private static void initialize(Minecraft minecraft) {
		if (controller != null || gameDirectoryFailure != null) {
			return;
		}
		synchronized (INITIALIZATION_LOCK) {
			if (controller != null || gameDirectoryFailure != null) {
				return;
			}
			HostController created;
			try {
				created = HostController.open(minecraft.getMinecraftDir().toPath());
			} catch (GameDirectoryLease.InUseException exception) {
				config = new BtaAnywhereConfig();
				gameDirectoryFailure = GameDirectoryLease.IN_USE_MESSAGE;
				LOGGER.error("BTA Anywhere game directory is in use by another client");
				return;
			} catch (IOException | RuntimeException exception) {
				config = new BtaAnywhereConfig();
				gameDirectoryFailure = GameDirectoryLease.UNAVAILABLE_MESSAGE;
				LOGGER.error("BTA Anywhere could not safely reserve the game directory");
				return;
			}
			try {
				ConfigStore store = new ConfigStore(minecraft.getMinecraftDir().toPath());
				try {
					config = store.load();
				} catch (Exception exception) {
					LOGGER.error("Could not load BTA Anywhere configuration; using an in-memory default");
					config = new BtaAnywhereConfig();
				}
				Runtime.getRuntime().addShutdownHook(new Thread(created::close,
					"bta-anywhere-shutdown"));
				controller = created;
			} catch (RuntimeException exception) {
				created.close();
				config = new BtaAnywhereConfig();
				gameDirectoryFailure = GameDirectoryLease.UNAVAILABLE_MESSAGE;
				LOGGER.error("BTA Anywhere could not initialize with an exclusive game directory lease");
			}
		}
	}
}
