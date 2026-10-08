package net.antwire.rewind;

import net.antwire.rewind.network.RewindPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Takes snapshots of the running world and starts restores. A snapshot is taken after a full save with autosave paused,
 * so the copy (made on a background thread, the game keeps running) is consistent. A restore needs the world closed:
 * a dedicated server stops, restores and exits with a restart code; in singleplayer the client saves, leaves, restores
 * and reopens the world.
 */
public final class SnapshotManager {
	private static @Nullable SnapshotManager instance;
	private final MinecraftServer server;
	private volatile boolean busy;
	private long ticks;
	/** Set when this server is shutting down for a restore. */
	private volatile boolean restoring;
	/** Maps whose saving is paused while a snapshot is being copied. */
	private final List<ServerLevel> paused = new ArrayList<>();

	private SnapshotManager(MinecraftServer server) {
		this.server = server;
	}

	public static void start(MinecraftServer server) {
		instance = new SnapshotManager(server);
	}

	public static void stop() {
		instance = null;
	}

	public static @Nullable SnapshotManager get() {
		return instance;
	}


	public Path world() {
		return this.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}

	public String worldName() {
		return this.world().getFileName().toString();
	}

	public Path root() {
		return SnapshotStore.root(FabricLoader.getInstance().getGameDir(), this.worldName());
	}

	public boolean busy() {
		return this.busy;
	}

	public boolean restoring() {
		return this.restoring;
	}

	public List<SnapshotStore.Meta> list() {
		return SnapshotStore.list(this.root());
	}

	public static String timestamp() {
		return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
	}

	// ------------------------------------------------------------------ saving

	/** Starts a snapshot; {@code reply} gets progress and the result (on the server thread). */
	public boolean save(String name, String kind, String description, String by, Consumer<Component> reply) {
		if (this.busy || this.restoring) {
			reply.accept(Component.translatable("rewind.busy").withStyle(ChatFormatting.RED));
			return false;
		}
		if (!SnapshotStore.validName(name)) {
			reply.accept(Component.translatable("rewind.bad_name", name).withStyle(ChatFormatting.RED));
			return false;
		}
		this.busy = true;
		long start = System.currentTimeMillis();
		reply.accept(Component.translatable("rewind.saving", name).withStyle(ChatFormatting.GRAY));
		// a full save first, then no more writes until the copy is done
		this.server.saveEverything(true, true, true);
		// every map the server has - the overworld, nether, end and any multiverse world - is in the world folder
		this.paused.clear();
		for (ServerLevel level : this.server.getAllLevels()) {
			if (!level.noSave) {
				level.noSave = true;
				this.paused.add(level);
			}
		}
		SnapshotStore.Meta meta = new SnapshotStore.Meta();
		meta.name = name;
		meta.kind = kind;
		meta.description = description == null ? "" : description;
		meta.createdBy = by;
		meta.created = System.currentTimeMillis();
		meta.gameTime = this.server.overworld().getGameTime();
		for (var mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (!id.startsWith("fabric") && !id.equals("java") && !id.equals("minecraft") && !id.equals("mixinextras")) {
				meta.mods.add(id + " " + mod.getMetadata().getVersion().getFriendlyString());
			}
		}
		Path world = this.world();
		Path root = this.root();
		List<SnapshotStore.Meta> existing = SnapshotStore.list(root);
		SnapshotStore.Meta previous = existing.isEmpty() ? null : existing.getFirst();
		Thread t = new Thread(() -> {
			Component result;
			try {
				java.nio.file.Files.createDirectories(root);
				SnapshotStore.Meta done = SnapshotStore.create(world, root, meta, previous);
				double secs = (System.currentTimeMillis() - start) / 1000.0;
				result = Component.translatable("rewind.saved", name, done.maps.size(), SnapshotStore.size(done.bytes), SnapshotStore.size(done.newBytes),
						String.format(java.util.Locale.ROOT, "%.1f", secs)).withStyle(ChatFormatting.GREEN);
				Rewind.LOGGER.info("Snapshot {} saved: {} files, {} ({} new) in {} s; maps: {}", name, done.files, SnapshotStore.size(done.bytes),
						SnapshotStore.size(done.newBytes), secs, String.join(", ", done.maps));
				if ("auto".equals(kind)) {
					SnapshotStore.prune(root, "auto", RewindConfig.get().autoKeep);
				}
			} catch (Exception e) {
				Rewind.LOGGER.error("Snapshot {} failed", name, e);
				result = Component.translatable("rewind.failed", name, e.toString()).withStyle(ChatFormatting.RED);
			}
			Component r = result;
			this.server.execute(() -> {
				for (ServerLevel level : this.paused) {
					level.noSave = false;
				}
				this.paused.clear();
				this.busy = false;
				reply.accept(r);
				this.broadcastList();
			});
		}, "Rewind snapshot " + name);
		t.setDaemon(false);
		t.start();
		return true;
	}

	public void tick() {
		if (this.busy) {
			// a map that appears while a snapshot is being copied (a multiverse world created or loaded) must not
			// write into the copy either; it is paused like the others and resumes with them
			for (ServerLevel level : this.server.getAllLevels()) {
				if (!level.noSave && !this.paused.contains(level)) {
					level.noSave = true;
					this.paused.add(level);
				}
			}
		}
		int minutes = RewindConfig.get().autoIntervalMinutes;
		if (minutes <= 0 || ++this.ticks % (minutes * 1200L) != 0 || this.busy) {
			return;
		}
		this.save("auto-" + timestamp(), "auto", "Automatic snapshot", "server", c -> Rewind.LOGGER.info(c.getString()));
	}

	// ------------------------------------------------------------------ deleting

	public boolean delete(String name, Consumer<Component> reply) {
		if (this.busy) {
			reply.accept(Component.translatable("rewind.busy").withStyle(ChatFormatting.RED));
			return false;
		}
		if (!SnapshotStore.validName(name) || SnapshotStore.readMeta(this.root().resolve(name)) == null) {
			reply.accept(Component.translatable("rewind.unknown", name).withStyle(ChatFormatting.RED));
			return false;
		}
		try {
			SnapshotStore.delete(this.root(), name);
			reply.accept(Component.translatable("rewind.deleted", name).withStyle(ChatFormatting.YELLOW));
			this.broadcastList();
			return true;
		} catch (Exception e) {
			reply.accept(Component.translatable("rewind.failed", name, e.toString()).withStyle(ChatFormatting.RED));
			return false;
		}
	}

	// ------------------------------------------------------------------ restoring

	public boolean restore(String name, String by, Consumer<Component> reply) {
		if (this.busy || this.restoring) {
			reply.accept(Component.translatable("rewind.busy").withStyle(ChatFormatting.RED));
			return false;
		}
		if (!SnapshotStore.validName(name) || SnapshotStore.readMeta(this.root().resolve(name)) == null) {
			reply.accept(Component.translatable("rewind.unknown", name).withStyle(ChatFormatting.RED));
			return false;
		}
		SnapshotStore.Pending p = new SnapshotStore.Pending();
		p.snapshot = name;
		p.requestedBy = by;
		p.requestedAt = System.currentTimeMillis();
		try {
			SnapshotStore.writePending(this.root(), p);
		} catch (Exception e) {
			reply.accept(Component.translatable("rewind.failed", name, e.toString()).withStyle(ChatFormatting.RED));
			return false;
		}
		this.restoring = true;
		Rewind.LOGGER.warn("{} restores snapshot {} of {}", by, name, this.worldName());
		Component msg = Component.translatable("rewind.restoring", name, by).withStyle(ChatFormatting.GOLD);
		this.server.getPlayerList().broadcastSystemMessage(msg, false);
		if (this.server.isDedicatedServer()) {
			int delay = Math.max(0, RewindConfig.get().restoreWarningSeconds) * 20;
			Rewind.schedule(this.server, delay, () -> {
				for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
					player.connection.disconnect(Component.translatable("rewind.kick", name));
				}
				this.server.halt(false);
			});
		} else {
			// singleplayer / LAN: the host's client closes the world, restores and reopens it
			for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
				if (this.server.isSingleplayerOwner(player.nameAndId())) {
					ServerPlayNetworking.send(player, new RewindPayloads.RestoreNow(this.worldName(), name));
				}
			}
		}
		return true;
	}

	/** Sends the snapshot list to everyone who has the snapshot screen open. */
	public void broadcastList() {
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			if (Rewind.watching(player)) {
				ServerPlayNetworking.send(player, RewindPayloads.list(this, Rewind.mayManage(player)));
			}
		}
	}
}
