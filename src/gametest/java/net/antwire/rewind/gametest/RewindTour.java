package net.antwire.rewind.gametest;

import net.antwire.rewind.SnapshotStore;
import net.antwire.rewind.client.RewindClient;
import net.antwire.rewind.client.SnapshotScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.List;
import java.util.Locale;

/** Takes a snapshot, changes the world, restores, and checks that the world is back as it was. */
public class RewindTour implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		System.setProperty("rewind.test", "true");
		TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true)
				.adjustSettings(s -> s.getNormalPresetList().stream().filter(e -> e.preset() != null && e.preset().is(WorldPresets.FLAT)).findFirst()
						.ifPresent(s::setWorldType))
				.create();
		sp.getConnection().waitForChunksRender();
		sp.getServer().runCommand("gamemode creative @a");
		sp.getServer().runCommand("time set 6000");
		BlockPos marker = new BlockPos(2, -60, 2);
		// a gold tower: the state to keep
		sp.getServer().runOnServer(server -> build(server.overworld(), marker, Blocks.GOLD_BLOCK, 5));
		sp.getServer().runCommand("tp @a 2.5 -58 -6 0 20");
		context.waitTicks(20);
		sp.getServer().runCommand("snapshot save golden Gold tower");
		context.waitFor(mc -> !snapshots(mc).isEmpty(), 1200);
		System.out.println("[rewind-test] after save: " + describe(snapshots(null)));
		// everything changes: the tower becomes diamond, a TNT crater, time moves on
		sp.getServer().runOnServer(server -> {
			build(server.overworld(), marker, Blocks.DIAMOND_BLOCK, 8);
			server.overworld().setBlock(marker.offset(4, 0, 0), Blocks.TNT.defaultBlockState(), Block.UPDATE_ALL);
		});
		sp.getServer().runCommand("time set 18000");
		context.waitTicks(20);
		sp.getServer().runCommand("snapshot save diamond Diamond tower");
		context.waitFor(mc -> snapshots(mc).size() >= 2, 1200);
		System.out.println("[rewind-test] after second save: " + describe(snapshots(null)));
		String before = sp.getServer().computeOnServer(server -> state(server.overworld(), marker));
		System.out.println("[rewind-test] before restore: " + before);
		context.takeScreenshot("rewind_diamond");
		// the snapshot screen from the pause menu
		context.runOnClient(mc -> mc.gui.setScreen(new PauseScreen(true)));
		context.waitTicks(5);
		context.takeScreenshot("rewind_pause_menu");
		context.runOnClient(mc -> mc.gui.setScreen(new SnapshotScreen(mc.gui.screen())));
		context.waitTicks(20);
		context.takeScreenshot("rewind_screen");
		context.runOnClient(mc -> mc.gui.setScreen(null));
		// restore: in the game the client leaves, restores and reopens on its own; here the framework closes and reopens
		sp.getServer().runCommand("snapshot restore golden confirm");
		context.waitTicks(10);
		var save = sp.getWorldSave();
		sp.close();
		context.waitFor(mc -> RewindClient.lastRestored.equals("golden"), 1200);
		System.out.println("[rewind-test] client restored the snapshot while the world was closed");
		sp = save.open();
		sp.getConnection().waitForChunksRender();
		String after = sp.getServer().computeOnServer(server -> state(server.overworld(), marker));
		long time = sp.getServer().computeOnServer(server -> server.overworld().getOverworldClockTime() % 24000);
		System.out.println("[rewind-test] after restore: " + after + ", day time " + time);
		System.out.println("[rewind-test] snapshots now: " + describe(snapshots(null)));
		System.out.println("[rewind-test] RESULT " + (after.equals("gold x5") && time < 12000 ? "PASS" : "FAIL"));
		context.takeScreenshot("rewind_restored");
		// and back again: the before-restore snapshot undoes the restore
		String undo = snapshots(null).stream().filter(m -> "before-restore".equals(m.kind)).map(m -> m.name).findFirst().orElse("");
		sp.getServer().runCommand("snapshot restore " + undo + " confirm");
		context.waitTicks(10);
		save = sp.getWorldSave();
		sp.close();
		context.waitFor(mc -> RewindClient.lastRestored.equals(undo), 1200);
		sp = save.open();
		sp.getConnection().waitForChunksRender();
		String undone = sp.getServer().computeOnServer(server -> state(server.overworld(), marker));
		System.out.println("[rewind-test] after undoing the restore: " + undone + " -> " + (undone.equals("diamond x8 +tnt") ? "PASS" : "FAIL"));
		System.out.println("[rewind-test] snapshots at the end: " + describe(snapshots(null)));
		sp.close();
	}

	private static void build(ServerLevel level, BlockPos base, Block block, int height) {
		for (int y = 0; y < 10; y++) {
			level.setBlock(base.above(y), y < height ? block.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	private static String state(ServerLevel level, BlockPos base) {
		Block b = level.getBlockState(base).getBlock();
		int h = 0;
		while (h < 10 && level.getBlockState(base.above(h)).is(b)) h++;
		String name = b == Blocks.GOLD_BLOCK ? "gold" : b == Blocks.DIAMOND_BLOCK ? "diamond" : b.getDescriptionId();
		return name + " x" + h + (level.getBlockState(base.offset(4, 0, 0)).is(Blocks.TNT) ? " +tnt" : "");
	}

	private static List<SnapshotStore.Meta> snapshots(Object ignored) {
		var root = FabricLoader.getInstance().getGameDir().resolve("snapshots");
		try (var dirs = java.nio.file.Files.list(root)) {
			var world = dirs.findFirst().orElse(null);
			return world == null ? List.of() : SnapshotStore.list(world);
		} catch (Exception e) {
			return List.of();
		}
	}

	private static String describe(List<SnapshotStore.Meta> list) {
		StringBuilder b = new StringBuilder();
		for (SnapshotStore.Meta m : list) {
			b.append(String.format(Locale.ROOT, "[%s %s %d files %s, %s new] ", m.name, m.kind, m.files, SnapshotStore.size(m.bytes), SnapshotStore.size(m.newBytes)));
		}
		return b.toString();
	}
}
