package net.antwire.rewind.client;

import net.antwire.rewind.Rewind;
import net.antwire.rewind.SnapshotStore;
import net.antwire.rewind.network.RewindPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public class RewindClient implements ClientModInitializer {
	private static String restoreWorld;
	private static String restoreSnapshot;
	/** 0 idle, 1 leaving the world, 2 copying files. */
	private static int phase;
	/** Game tests close and reopen worlds through their own framework; the restore itself runs as usual. */
	private static boolean test() {
		return Boolean.getBoolean("rewind.test");
	}
	/** For tests: the last snapshot restored by the client. */
	public static volatile String lastRestored = "";

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(RewindPayloads.ListReply.TYPE, (p, ctx) -> ctx.client().execute(() -> SnapshotScreen.receive(p)));
		// packet handlers do not run on the client thread: hand the work over
		ClientPlayNetworking.registerGlobalReceiver(RewindPayloads.RestoreNow.TYPE, (p, ctx) -> ctx.client().execute(() -> {
			Minecraft mc = ctx.client();
			restoreWorld = p.world();
			restoreSnapshot = p.snapshot();
			phase = 1;
			if (!test()) {
				mc.disconnectWithSavingScreen();
			}
		}));
		ClientTickEvents.END_CLIENT_TICK.register(RewindClient::tick);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen && ClientPlayNetworking.canSend(RewindPayloads.Action.TYPE)) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("rewind.screen.button"),
						b -> client.gui.setScreen(new SnapshotScreen(screen))).bounds(4, height - 24, 98, 20).build());
			}
		});
	}

	/** Once the integrated server has stopped and released the world: put the snapshot in place, then reopen. */
	private static void tick(Minecraft mc) {
		if (phase != 1 || mc.getSingleplayerServer() != null || mc.level != null) {
			return;
		}
		phase = 2;
		String world = restoreWorld;
		String snapshot = restoreSnapshot;
		if (!test()) {
			mc.gui.setScreen(new GenericMessageScreen(Component.translatable("rewind.client.restoring", snapshot)));
		}
		Path game = FabricLoader.getInstance().getGameDir();
		Path dir = mc.getLevelSource().getBaseDir().resolve(world);
		Thread t = new Thread(() -> {
			Exception error = null;
			for (int attempt = 0; attempt < 20; attempt++) {
				try {
					SnapshotStore.applyPending(dir, SnapshotStore.root(game, world));
					error = null;
					break;
				} catch (Exception e) {
					// the server may still be letting go of a file; try again
					error = e;
					try {
						Thread.sleep(500);
					} catch (InterruptedException ignored) {
						break;
					}
				}
			}
			Exception failed = error;
			mc.execute(() -> {
				phase = 0;
				if (failed != null) {
					Rewind.LOGGER.error("Restoring snapshot {} failed", snapshot, failed);
					mc.gui.setScreen(new TitleScreen());
				} else {
					Rewind.LOGGER.info("Restored snapshot {} of {}, reopening", snapshot, world);
					lastRestored = snapshot;
					if (!test()) {
						mc.createWorldOpenFlows().openWorld(world, () -> mc.gui.setScreen(new TitleScreen()));
					}
				}
			});
		}, "Rewind restore");
		t.start();
	}
}
