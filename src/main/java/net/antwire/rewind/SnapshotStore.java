package net.antwire.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Snapshots on disk: {@code <game dir>/<snapshotDir>/<world folder>/<snapshot>/} holds a full copy of the world folder
 * plus {@code snapshot.json} (what it is) and {@code manifest.json} (size and modification time of every file).
 * A new snapshot hard-links every file that has not changed since the previous one, so snapshots share unchanged
 * region files and cost little more than what changed. Snapshot files are never written to again, which is what makes
 * the sharing safe; restoring always copies.
 * <p>
 * Everything here is plain file work without Minecraft classes, so the dedicated server can use it before the game
 * starts and the client while no world is open.
 */
public final class SnapshotStore {
	public static final String META = "snapshot.json";
	public static final String MANIFEST = "manifest.json";
	public static final String PENDING = "pending_restore.json";
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.+-]{1,48}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** What a snapshot is. */
	public static class Meta {
		public String name;
		public String kind = "manual";
		public String description = "";
		public String createdBy = "";
		public long created;
		public long gameTime;
		public long files;
		/** Size of the world in this snapshot. */
		public long bytes;
		/** Bytes actually written for this snapshot (the rest is shared with the one before). */
		public long newBytes;
		public List<String> mods = new ArrayList<>();
		/** Every map (dimension) in the snapshot, e.g. minecraft:overworld or a multiverse world. */
		public List<String> maps = new ArrayList<>();
	}

	/** The map a world-folder path belongs to ({@code dimensions/<namespace>/<name>/...}), or null. */
	public static String mapOf(String rel) {
		String[] p = rel.split("/");
		return p.length >= 4 && p[0].equals("dimensions") ? p[1] + ":" + p[2] : null;
	}

	public static class Pending {
		public String snapshot;
		public String requestedBy = "";
		public long requestedAt;
	}

	private SnapshotStore() {
	}

	public static boolean validName(String name) {
		return name != null && NAME.matcher(name).matches() && !name.startsWith(".");
	}

	public static Path root(Path gameDir, String world) {
		return gameDir.resolve(RewindConfig.get().snapshotDir).resolve(world);
	}

	/** All snapshots of a world, newest first. */
	public static List<Meta> list(Path root) {
		List<Meta> out = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return out;
		}
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path d : (Iterable<Path>) dirs::iterator) {
				Meta m = readMeta(d);
				if (m != null) {
					out.add(m);
				}
			}
		} catch (IOException e) {
			Rewind.LOGGER.error("Could not list {}", root, e);
		}
		out.sort(Comparator.comparingLong((Meta m) -> m.created).reversed());
		return out;
	}

	public static Meta readMeta(Path dir) {
		Path f = dir.resolve(META);
		if (!Files.isRegularFile(f)) {
			return null;
		}
		try (Reader r = Files.newBufferedReader(f)) {
			return GSON.fromJson(r, Meta.class);
		} catch (Exception e) {
			return null;
		}
	}

	private static void write(Path file, Object obj) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp)) {
			GSON.toJson(obj, w);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static Map<String, long[]> readManifest(Path dir) {
		Path f = dir.resolve(MANIFEST);
		if (!Files.isRegularFile(f)) {
			return Map.of();
		}
		try (Reader r = Files.newBufferedReader(f)) {
			Map<String, long[]> m = GSON.fromJson(r, new TypeToken<Map<String, long[]>>() {
			}.getType());
			return m == null ? Map.of() : m;
		} catch (Exception e) {
			return Map.of();
		}
	}

	private static boolean skipped(Path rel) {
		String name = rel.getFileName() == null ? "" : rel.getFileName().toString();
		return name.equals("session.lock") || name.endsWith(".tmp") && rel.getNameCount() == 1;
	}

	/**
	 * Copies the world folder into a new snapshot. Unchanged files (same size and modification time as in
	 * {@code previous}) become hard links to the previous snapshot's copy.
	 */
	public static Meta create(Path world, Path root, Meta meta, Meta previous) throws IOException {
		Path target = root.resolve(meta.name);
		Path partial = root.resolve(".partial-" + meta.name);
		deleteTree(partial);
		Files.createDirectories(partial);
		Path prevDir = previous == null ? null : root.resolve(previous.name);
		Map<String, long[]> prevManifest = prevDir == null ? Map.of() : readManifest(prevDir);
		Map<String, long[]> manifest = new HashMap<>();
		long[] stats = new long[3];
		Files.walkFileTree(world, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(partial.resolve(world.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path rel = world.relativize(file);
				if (skipped(rel) || !attrs.isRegularFile()) {
					return FileVisitResult.CONTINUE;
				}
				String key = rel.toString().replace('\\', '/');
				long size = attrs.size();
				long mtime = attrs.lastModifiedTime().toMillis();
				Path dest = partial.resolve(rel.toString());
				long[] before = prevManifest.get(key);
				boolean linked = false;
				if (before != null && before[0] == size && before[1] == mtime) {
					try {
						Files.createLink(dest, prevDir.resolve(rel.toString()));
						linked = true;
					} catch (IOException | UnsupportedOperationException e) {
						linked = false;
					}
				}
				if (!linked) {
					Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					stats[2] += size;
				}
				manifest.put(key, new long[]{size, mtime});
				stats[0]++;
				stats[1] += size;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				Rewind.LOGGER.warn("Skipping unreadable file {}: {}", file, exc.toString());
				return FileVisitResult.CONTINUE;
			}
		});
		meta.files = stats[0];
		meta.bytes = stats[1];
		meta.newBytes = stats[2];
		meta.maps = manifest.keySet().stream().map(SnapshotStore::mapOf).filter(java.util.Objects::nonNull).distinct().sorted().toList();
		write(partial.resolve(MANIFEST), manifest);
		write(partial.resolve(META), meta);
		deleteTree(target);
		Files.move(partial, target);
		return meta;
	}

	/**
	 * Puts a snapshot in place of the world folder (the world must be closed). The current world folder is first set
	 * aside as a snapshot named {@code safetyName} (a rename, so it costs nothing), unless it is null.
	 */
	public static void restore(Path world, Path root, String snapshot, String safetyName, Meta safetyMeta) throws IOException {
		Path source = root.resolve(snapshot);
		if (readMeta(source) == null) {
			throw new IOException("No snapshot named " + snapshot);
		}
		Path fresh = world.resolveSibling(world.getFileName() + ".rewind-restoring");
		deleteTree(fresh);
		copyTree(source, fresh);
		Files.deleteIfExists(fresh.resolve(META));
		Files.deleteIfExists(fresh.resolve(MANIFEST));
		if (Files.exists(world)) {
			if (safetyName != null) {
				Path aside = root.resolve(safetyName);
				deleteTree(aside);
				Files.deleteIfExists(world.resolve("session.lock"));
				try {
					Files.move(world, aside);
				} catch (IOException e) {
					// another file system: copy, then delete
					copyTree(world, aside);
					deleteTree(world);
				}
				safetyMeta.name = safetyName;
				Map<String, long[]> manifest = new HashMap<>();
				long[] stats = new long[2];
				try (Stream<Path> walk = Files.walk(aside)) {
					for (Path f : (Iterable<Path>) walk::iterator) {
						if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
							long size = Files.size(f);
							manifest.put(aside.relativize(f).toString().replace('\\', '/'), new long[]{size, Files.getLastModifiedTime(f).toMillis()});
							stats[0]++;
							stats[1] += size;
						}
					}
				}
				safetyMeta.files = stats[0];
				safetyMeta.bytes = stats[1];
				safetyMeta.newBytes = stats[1];
				write(aside.resolve(MANIFEST), manifest);
				write(aside.resolve(META), safetyMeta);
			} else {
				deleteTree(world);
			}
		}
		Files.move(fresh, world);
	}

	public static void delete(Path root, String snapshot) throws IOException {
		deleteTree(root.resolve(snapshot));
	}

	public static Pending readPending(Path root) {
		Path f = root.resolve(PENDING);
		if (!Files.isRegularFile(f)) {
			return null;
		}
		try (Reader r = Files.newBufferedReader(f)) {
			return GSON.fromJson(r, Pending.class);
		} catch (Exception e) {
			return null;
		}
	}

	public static void writePending(Path root, Pending p) throws IOException {
		Files.createDirectories(root);
		write(root.resolve(PENDING), p);
	}

	public static void clearPending(Path root) {
		try {
			Files.deleteIfExists(root.resolve(PENDING));
		} catch (IOException e) {
			Rewind.LOGGER.error("Could not remove {}", root.resolve(PENDING), e);
		}
	}

	/**
	 * Carries out a pending restore if there is one: puts the snapshot in place and sets the old world aside as a
	 * "before-restore" snapshot. Returns the snapshot name, or null if nothing was pending.
	 */
	public static String applyPending(Path world, Path root) throws IOException {
		Pending p = readPending(root);
		if (p == null) {
			return null;
		}
		String safety = "before-restore-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Meta m = new Meta();
		m.kind = "before-restore";
		m.description = "The world as it was before snapshot " + p.snapshot + " was restored";
		m.createdBy = p.requestedBy;
		m.created = System.currentTimeMillis();
		restore(world, root, p.snapshot, safety, m);
		clearPending(root);
		prune(root, "before-restore", RewindConfig.get().keepBeforeRestore);
		return p.snapshot;
	}

	/** Deletes the oldest snapshots of a kind beyond {@code keep}. */
	public static void prune(Path root, String kind, int keep) {
		List<Meta> all = list(root);
		int n = 0;
		for (Meta m : all) {
			if (kind.equals(m.kind) && ++n > Math.max(0, keep)) {
				try {
					delete(root, m.name);
					Rewind.LOGGER.info("Pruned old snapshot {}", m.name);
				} catch (IOException e) {
					Rewind.LOGGER.error("Could not delete {}", m.name, e);
				}
			}
		}
	}

	public static void copyTree(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!skipped(from.relativize(file))) {
					Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
				Files.delete(d);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Default descriptions the mod writes into snapshot.json (in English); shown translated. */
	public static final String AUTO_DESCRIPTION = "Automatic snapshot";
	private static final java.util.regex.Pattern BEFORE_RESTORE = java.util.regex.Pattern.compile("The world as it was before snapshot (.+) was restored");

	/** The description of a snapshot for players: the mod's own default texts become translations, anything typed in stays as it is. */
	public static net.minecraft.network.chat.MutableComponent describe(String kind, String description) {
		if ("auto".equals(kind) && AUTO_DESCRIPTION.equals(description)) {
			return net.minecraft.network.chat.Component.translatable("rewind.description.auto");
		}
		if ("before-restore".equals(kind) && description != null) {
			java.util.regex.Matcher m = BEFORE_RESTORE.matcher(description);
			if (m.matches()) {
				return net.minecraft.network.chat.Component.translatable("rewind.description.before_restore", m.group(1));
			}
		}
		return net.minecraft.network.chat.Component.literal(description == null ? "" : description);
	}

	/** The kind of a snapshot (manual, auto, before-restore) for players. */
	public static net.minecraft.network.chat.MutableComponent kindName(String kind) {
		return net.minecraft.network.chat.Component.translatableWithFallback("rewind.kind." + kind.replace('-', '_'), kind);
	}

	public static String size(long bytes) {
		if (bytes >= 1L << 30) return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
		if (bytes >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
		return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
	}
}
