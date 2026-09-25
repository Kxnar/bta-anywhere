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
		if (gameDirectoryFailure != null) {
			minecraft.displayScreen(new GameDirectoryInUseScreen(minecraft.currentScreen, gameDirectoryFailure));
			return;
		}
		if (controller.hasRecoveryArtifacts()) {
			minecraft.displayScreen(new RecoveryScreen(minecraft.currentScreen));
		}
	}

	public static HostController controller(Minecraft minecraft) {
		initialize(minecraft);
		if (controller == null) {
			throw new IllegalStateException("BTA Anywhere cannot use this game directory: " + gameDirectoryFailure);
		}
		return controller;
	}

	public static boolean hasRecoveryArtifacts(Minecraft minecraft) {
		initialize(minecraft);
		return controller != null && controller.hasRecoveryArtifacts();
	}

	public static BtaAnywhereConfig config(Minecraft minecraft) {
		initialize(minecraft);
		return config;
	}

	public static void openHostingScreen(Minecraft minecraft, Screen parent) {
		initialize(minecraft);
		minecraft.displayScreen(controller == null
			? new GameDirectoryInUseScreen(parent, gameDirectoryFailure)
			: new HostingScreen(parent));
	}

	public static void clientTick(Minecraft minecraft) {
		BlockedOpenScreen blocked = BLOCKED_OPEN_SCREEN.getAndSet(null);
		if (blocked != null) {
			minecraft.displayScreen(switch (blocked) {
				case RECOVERY -> new RecoveryScreen(minecraft.currentScreen);
				case HOSTING -> new HostingScreen(minecraft.currentScreen);
				case GAME_DIRECTORY -> new GameDirectoryInUseScreen(minecraft.currentScreen,
					gameDirectoryFailure);
			});
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
		initialize(minecraft);
		HostController active = controller;
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
		if (controller == null) {
			BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.GAME_DIRECTORY);
			return true;
		}
		if (!WorldOpenGuard.blocksNewWorld(minecraft.getMinecraftDir().toPath())) {
			return false;
		}
		BLOCKED_OPEN_SCREEN.set(BlockedOpenScreen.RECOVERY);
		return true;
	}

	private static void initialize(Minecraft minecraft) {
		if (controller != null || gameDirectoryFailure != null) {
			return;
		}
		synchronized (INITIALIZATION_LOCK) {
			if (controller != null || gameDirectoryFailure != null) {
				return;
			}
			GameDirectoryLease acquired;
			try {
				acquired = GameDirectoryLease.acquire(minecraft.getMinecraftDir().toPath());
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
				HostController created = new HostController(minecraft.getMinecraftDir().toPath(), acquired);
				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
					try {
						created.close();
					} finally {
						try {
							acquired.close();
						} catch (IOException exception) {
							LOGGER.error("Could not release BTA Anywhere game directory lease");
						}
					}
				}, "bta-anywhere-shutdown"));
				controller = created;
			} catch (RuntimeException exception) {
				try {
					acquired.close();
				} catch (IOException ignored) {
					// The failed client cannot continue; Windows releases the handle on exit.
				}
				config = new BtaAnywhereConfig();
				gameDirectoryFailure = GameDirectoryLease.UNAVAILABLE_MESSAGE;
				LOGGER.error("BTA Anywhere could not initialize with an exclusive game directory lease");
			}
		}
	}
}
