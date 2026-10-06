package net.antwire.rewind.network;

import net.antwire.rewind.Rewind;
import net.antwire.rewind.RewindConfig;
import net.antwire.rewind.SnapshotManager;
import net.antwire.rewind.SnapshotStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public final class RewindPayloads {
	public static final int OPEN = 0, SAVE = 1, RESTORE = 2, DELETE = 3, CLOSE = 4;

	private RewindPayloads() {
	}

	/** Client → server: what the snapshot screen wants. */
	public record Action(int action, String name, String text) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(Rewind.id("action"));
		public static final StreamCodec<FriendlyByteBuf, Action> CODEC = CustomPacketPayload.codec((p, b) -> {
			b.writeVarInt(p.action);
			b.writeUtf(p.name, 64);
			b.writeUtf(p.text, 256);
		}, b -> new Action(b.readVarInt(), b.readUtf(64), b.readUtf(256)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Entry(String name, String kind, String description, String by, long created, long bytes, long newBytes) {
	}

	/** Server → client: the snapshots of the current world. */
	public record ListReply(String world, List<Entry> entries, boolean busy, boolean canManage, int autoMinutes) implements CustomPacketPayload {
		public static final Type<ListReply> TYPE = new Type<>(Rewind.id("list"));
		public static final StreamCodec<FriendlyByteBuf, ListReply> CODEC = CustomPacketPayload.codec((p, b) -> {
			b.writeUtf(p.world);
			b.writeVarInt(p.entries.size());
			for (Entry e : p.entries) {
				b.writeUtf(e.name);
				b.writeUtf(e.kind);
				b.writeUtf(e.description, 512);
				b.writeUtf(e.by);
				b.writeLong(e.created);
				b.writeLong(e.bytes);
				b.writeLong(e.newBytes);
			}
			b.writeBoolean(p.busy);
			b.writeBoolean(p.canManage);
			b.writeVarInt(p.autoMinutes);
		}, b -> {
			String world = b.readUtf();
			int n = b.readVarInt();
			List<Entry> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(new Entry(b.readUtf(), b.readUtf(), b.readUtf(512), b.readUtf(), b.readLong(), b.readLong(), b.readLong()));
			}
			return new ListReply(world, list, b.readBoolean(), b.readBoolean(), b.readVarInt());
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server → host client in singleplayer/LAN: close the world, restore {@code snapshot} and reopen it. */
	public record RestoreNow(String world, String snapshot) implements CustomPacketPayload {
		public static final Type<RestoreNow> TYPE = new Type<>(Rewind.id("restore_now"));
		public static final StreamCodec<FriendlyByteBuf, RestoreNow> CODEC = CustomPacketPayload.codec((p, b) -> {
			b.writeUtf(p.world);
			b.writeUtf(p.snapshot);
		}, b -> new RestoreNow(b.readUtf(), b.readUtf()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static ListReply list(SnapshotManager m, boolean canManage) {
		List<Entry> entries = new ArrayList<>();
		for (SnapshotStore.Meta s : m.list()) {
			entries.add(new Entry(s.name, s.kind, s.description, s.createdBy, s.created, s.bytes, s.newBytes));
		}
		return new ListReply(m.worldName(), entries, m.busy() || m.restoring(), canManage, RewindConfig.get().autoIntervalMinutes);
	}
}
