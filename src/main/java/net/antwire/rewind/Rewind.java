package net.antwire.rewind;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.antwire.rewind.network.RewindPayloads;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Rewind implements ModInitializer {
	public static final String MOD_ID = "rewind";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Set<UUID> WATCHERS = new HashSet<>();
	private static final List<Object[]> TASKS = new ArrayList<>();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		RewindConfig.get();
		PayloadTypeRegistry.serverboundPlay().register(RewindPayloads.Action.TYPE, RewindPayloads.Action.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RewindPayloads.ListReply.TYPE, RewindPayloads.ListReply.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RewindPayloads.RestoreNow.TYPE, RewindPayloads.RestoreNow.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RewindPayloads.Action.TYPE, (p, ctx) -> this.action(ctx.player(), p));
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(SnapshotManager::start);
		ServerLifecycleEvents.SERVER_STOPPED.register(Rewind::stopped);
		ServerTickEvents.END_SERVER_TICK.register(Rewind::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCHERS.remove(handler.getPlayer().getUUID()));
		LOGGER.info("Rewind: world snapshots ready ({}/<world>/)", RewindConfig.get().snapshotDir);
	}

	public static void schedule(MinecraftServer server, int ticks, Runnable task) {
		TASKS.add(new Object[]{(long) server.getTickCount() + ticks, task});
	}

	private static void tick(MinecraftServer server) {
		if (!TASKS.isEmpty()) {
			List<Object[]> due = new ArrayList<>();
			TASKS.removeIf(t -> {
				if ((long) t[0] <= server.getTickCount()) {
					due.add(t);
					return true;
				}
				return false;
			});
			due.forEach(t -> ((Runnable) t[1]).run());
		}
		SnapshotManager m = SnapshotManager.get();
		if (m != null) {
			m.tick();
		}
	}

	/**
	 * After a dedicated server has stopped for a restore: wait until it has really finished (and released the world),
	 * put the snapshot in place, and exit with the restart code so a supervisor starts it again.
	 */
	private static void stopped(MinecraftServer server) {
		SnapshotManager m = SnapshotManager.get();
		SnapshotManager.stop();
		TASKS.clear();
		WATCHERS.clear();
		if (m == null || !server.isDedicatedServer() || SnapshotStore.readPending(m.root()) == null) {
			return;
		}
		Thread serverThread = Thread.currentThread();
		Thread t = new Thread(() -> {
			try {
				serverThread.join(60_000);
				String done = SnapshotStore.applyPending(m.world(), m.root());
				LOGGER.warn("Restored snapshot {} into {}", done, m.world());
			} catch (Exception e) {
				LOGGER.error("Restoring the snapshot failed; it stays pending and is retried at the next start", e);
			}
			int code = RewindConfig.get().restartExitCode;
			if (m.restoring() && code != 0) {
				LOGGER.warn("Exiting with code {} so the server is restarted with the restored world", code);
				System.exit(code);
			}
		}, "Rewind restore");
		t.setDaemon(false);
		t.start();
	}

	public static boolean mayManage(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server.isSingleplayerOwner(player.nameAndId()) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static boolean mayManage(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player == null ? source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) : mayManage(player);
	}

	public static boolean watching(ServerPlayer player) {
		return WATCHERS.contains(player.getUUID());
	}

	// ------------------------------------------------------------------ the snapshot screen

	private void action(ServerPlayer player, RewindPayloads.Action a) {
		SnapshotManager m = SnapshotManager.get();
		if (m == null) {
			return;
		}
		if (a.action() == RewindPayloads.CLOSE) {
			WATCHERS.remove(player.getUUID());
			return;
		}
		if (a.action() == RewindPayloads.OPEN) {
			WATCHERS.add(player.getUUID());
			ServerPlayNetworking.send(player, RewindPayloads.list(m, mayManage(player)));
			return;
		}
		if (!mayManage(player)) {
			player.sendSystemMessage(Component.translatable("rewind.not_allowed").withStyle(ChatFormatting.RED));
			return;
		}
		String by = player.getName().getString();
		switch (a.action()) {
			case RewindPayloads.SAVE -> m.save(a.name().isBlank() ? "snapshot-" + SnapshotManager.timestamp() : a.name(), "manual", a.text(), by,
					player::sendSystemMessage);
			case RewindPayloads.RESTORE -> m.restore(a.name(), by, player::sendSystemMessage);
			case RewindPayloads.DELETE -> m.delete(a.name(), player::sendSystemMessage);
			default -> {
			}
		}
		ServerPlayNetworking.send(player, RewindPayloads.list(m, true));
	}

	// ------------------------------------------------------------------ commands

	private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, builder) -> {
		SnapshotManager m = SnapshotManager.get();
		if (m != null) {
			for (SnapshotStore.Meta s : m.list()) {
				builder.suggest(s.name);
			}
		}
		return builder.buildFuture();
	};

	private static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("snapshot").requires(Rewind::mayManage)
				.then(Commands.literal("list").executes(Rewind::list))
				.then(Commands.literal("save").executes(c -> save(c, null, ""))
						.then(Commands.argument("name", StringArgumentType.word()).executes(c -> save(c, StringArgumentType.getString(c, "name"), ""))
								.then(Commands.argument("description", StringArgumentType.greedyString())
										.executes(c -> save(c, StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "description"))))))
				.then(Commands.literal("info").then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(Rewind::info)))
				.then(Commands.literal("restore").then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> confirm(c, "restore"))
						.then(Commands.literal("confirm").executes(c -> restore(c)))))
				.then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> confirm(c, "delete"))
						.then(Commands.literal("confirm").executes(c -> delete(c)))))
				.then(Commands.literal("auto").executes(Rewind::autoShow)
						.then(Commands.literal("off").executes(c -> auto(c, 0, RewindConfig.get().autoKeep)))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
								.executes(c -> auto(c, IntegerArgumentType.getInteger(c, "minutes"), RewindConfig.get().autoKeep))
								.then(Commands.argument("keep", IntegerArgumentType.integer(1, 1000))
										.executes(c -> auto(c, IntegerArgumentType.getInteger(c, "minutes"), IntegerArgumentType.getInteger(c, "keep")))))));
	}

	private static SnapshotManager manager(CommandContext<CommandSourceStack> c) {
		SnapshotManager m = SnapshotManager.get();
		if (m == null) {
			c.getSource().sendFailure(Component.literal("Rewind is not running"));
		}
		return m;
	}

	private static String by(CommandSourceStack s) {
		return s.getTextName();
	}

	private static int save(CommandContext<CommandSourceStack> c, String name, String description) {
		SnapshotManager m = manager(c);
		if (m == null) return 0;
		CommandSourceStack s = c.getSource();
		return m.save(name == null ? "snapshot-" + SnapshotManager.timestamp() : name, "manual", description, by(s), msg -> s.sendSuccess(() -> msg, true)) ? 1 : 0;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		SnapshotManager m = manager(c);
		if (m == null) return 0;
		List<SnapshotStore.Meta> all = m.list();
		CommandSourceStack s = c.getSource();
		if (all.isEmpty()) {
			s.sendSuccess(() -> Component.translatable("rewind.none", m.worldName()), false);
			return 0;
		}
		long total = 0;
		for (SnapshotStore.Meta x : all) total += x.newBytes;
		long t = total;
		s.sendSuccess(() -> Component.translatable("rewind.list_head", all.size(), m.worldName(), SnapshotStore.size(t)).withStyle(ChatFormatting.GOLD), false);
		SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		for (SnapshotStore.Meta x : all) {
			MutableComponent line = Component.literal(" " + x.name).withStyle(kindColor(x.kind))
					.append(Component.literal("  " + f.format(new Date(x.created)) + "  " + SnapshotStore.size(x.bytes)).withStyle(ChatFormatting.GRAY));
			if (!x.description.isEmpty()) {
				line.append(Component.literal("  " + x.description).withStyle(ChatFormatting.DARK_GRAY));
			}
			line.append(Component.literal("  [restore]").withStyle(s2 -> s2.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.SuggestCommand("/snapshot restore " + x.name))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("rewind.click_restore")))));
			s.sendSuccess(() -> line, false);
		}
		return all.size();
	}

	private static ChatFormatting kindColor(String kind) {
		return switch (kind) {
			case "auto" -> ChatFormatting.GREEN;
			case "before-restore" -> ChatFormatting.YELLOW;
			default -> ChatFormatting.WHITE;
		};
	}

	private static int info(CommandContext<CommandSourceStack> c) {
		SnapshotManager m = manager(c);
		if (m == null) return 0;
		String name = StringArgumentType.getString(c, "name");
		SnapshotStore.Meta x = SnapshotStore.validName(name) ? SnapshotStore.readMeta(m.root().resolve(name)) : null;
		if (x == null) {
			c.getSource().sendFailure(Component.translatable("rewind.unknown", name));
			return 0;
		}
		String text = String.format(java.util.Locale.ROOT, "%s (%s)%n  taken %s by %s, day %d%n  %d files, %s (%s new)%n  %s%n  maps: %s%n  mods: %s",
				x.name, x.kind, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(x.created)), x.createdBy, x.gameTime / 24000,
				x.files, SnapshotStore.size(x.bytes), SnapshotStore.size(x.newBytes), x.description,
				x.maps == null || x.maps.isEmpty() ? "(not recorded)" : String.join(", ", x.maps), String.join(", ", x.mods));
		c.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int confirm(CommandContext<CommandSourceStack> c, String what) {
		SnapshotManager m = manager(c);
		if (m == null) return 0;
		String name = StringArgumentType.getString(c, "name");
		if (!SnapshotStore.validName(name) || SnapshotStore.readMeta(m.root().resolve(name)) == null) {
			c.getSource().sendFailure(Component.translatable("rewind.unknown", name));
			return 0;
		}
		String cmd = "/snapshot " + what + " " + name + " confirm";
		c.getSource().sendSuccess(() -> Component.translatable("rewind.confirm_" + what, name).withStyle(ChatFormatting.GOLD)
				.append(Component.literal(" [" + Component.translatable("rewind.confirm").getString() + "]").withStyle(s -> s.withColor(ChatFormatting.RED)
						.withBold(true).withClickEvent(new ClickEvent.RunCommand(cmd)).withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd))))), false);
		return 1;
	}

	private static int restore(CommandContext<CommandSourceStack> c) {
		SnapshotManager m = manager(c);
		if (m == null) return 0;
		CommandSourceStack s = c.getSource();
		return m.restore(StringArgumentType.getString(c, "name"), by(s), msg -> s.sendSuccess(() -> msg, true)) ? 1 : 0;
	}

	private static int delete(CommandContext<CommandSourceStack> c) {
		SnapshotManager m = manager(c);
		if (m == null) return 0;
		CommandSourceStack s = c.getSource();
		return m.delete(StringArgumentType.getString(c, "name"), msg -> s.sendSuccess(() -> msg, true)) ? 1 : 0;
	}

	private static int autoShow(CommandContext<CommandSourceStack> c) {
		RewindConfig cfg = RewindConfig.get();
		c.getSource().sendSuccess(() -> cfg.autoIntervalMinutes > 0
				? Component.translatable("rewind.auto_on", cfg.autoIntervalMinutes, cfg.autoKeep)
				: Component.translatable("rewind.auto_off"), false);
		return cfg.autoIntervalMinutes;
	}

	private static int auto(CommandContext<CommandSourceStack> c, int minutes, int keep) {
		RewindConfig cfg = RewindConfig.get();
		cfg.autoIntervalMinutes = minutes;
		cfg.autoKeep = keep;
		cfg.save();
		return autoShow(c);
	}
}
