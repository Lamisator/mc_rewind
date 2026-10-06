package net.antwire.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/rewind.json. On a server the server's copy counts. */
public class RewindConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static RewindConfig instance;

	/** Folder for the snapshots, relative to the game (or server) directory. */
	public String snapshotDir = "snapshots";
	/** Minutes between automatic snapshots; 0 switches them off. */
	public int autoIntervalMinutes = 0;
	/** How many automatic snapshots to keep (the oldest go first). */
	public int autoKeep = 12;
	/** How many "before-restore" snapshots to keep: each restore first sets the current world aside as one. */
	public int keepBeforeRestore = 5;
	/**
	 * Exit code of a dedicated server after it has restored a snapshot. A non-zero code makes a supervisor with
	 * "restart on failure" (systemd Restart=on-failure, most panels) start the server again; 0 just stops it.
	 */
	public int restartExitCode = 75;
	/** Seconds the players of a dedicated server get to read the restore message before they are disconnected. */
	public int restoreWarningSeconds = 5;

	public static RewindConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("rewind.json");
	}

	private static RewindConfig load() {
		RewindConfig c = null;
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p)) {
				c = GSON.fromJson(r, RewindConfig.class);
			} catch (Exception e) {
				Rewind.LOGGER.error("Could not read {}, using defaults", p, e);
			}
		}
		if (c == null) {
			c = new RewindConfig();
		}
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path())) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			Rewind.LOGGER.error("Could not write {}", path(), e);
		}
	}
}
