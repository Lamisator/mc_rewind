package net.antwire.rewind;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Before a dedicated server loads its world: finishes a restore that was requested but not yet carried out (for
 * example because the server was killed before it could stop cleanly).
 */
public class RewindPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		try {
			Path game = FabricLoader.getInstance().getGameDir();
			String levelName = "world";
			Path props = game.resolve("server.properties");
			if (Files.isRegularFile(props)) {
				Properties p = new Properties();
				try (Reader r = Files.newBufferedReader(props)) {
					p.load(r);
				}
				levelName = p.getProperty("level-name", "world");
			}
			Path world = game.resolve(levelName).toAbsolutePath().normalize();
			String done = SnapshotStore.applyPending(world, SnapshotStore.root(game, world.getFileName().toString()));
			if (done != null) {
				Rewind.LOGGER.warn("Restored snapshot {} into {} before start-up", done, world);
			}
		} catch (Exception e) {
			Rewind.LOGGER.error("Could not apply a pending snapshot restore", e);
		}
	}
}
