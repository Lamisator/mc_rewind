package net.antwire.rewind.client;

import net.antwire.rewind.SnapshotStore;
import net.antwire.rewind.network.RewindPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Lists the world's snapshots: take a new one, restore or delete (with a second click to confirm). */
public class SnapshotScreen extends Screen {
	private static final int W = 380;
	private static final int ROW = 22;
	private static RewindPayloads.@Nullable ListReply latest;
	private final Screen parent;
	private EditBox name;
	private EditBox description;
	private int scroll;
	private String armed = "";
	private long armedAt;

	public SnapshotScreen(Screen parent) {
		super(Component.translatable("rewind.screen.title"));
		this.parent = parent;
		latest = null;
	}

	public static void receive(RewindPayloads.ListReply reply) {
		latest = reply;
	}

	/** Height and visible rows follow the window, so the list fits at any GUI scale. */
	private int h() {
		return Math.min(250, this.height - 8);
	}

	private int rows() {
		return Math.max(2, (this.h() - 100) / ROW);
	}

	private int left() {
		return (this.width - W) / 2;
	}

	private int top() {
		return (this.height - this.h()) / 2;
	}

	@Override
	protected void init() {
		int x = this.left(), y = this.top();
		this.name = new EditBox(this.font, x + 10, y + 24, 110, 18, Component.translatable("rewind.screen.name"));
		this.name.setMaxLength(48);
		this.name.setHint(Component.translatable("rewind.screen.name_hint"));
		this.description = new EditBox(this.font, x + 126, y + 24, 160, 18, Component.translatable("rewind.screen.description"));
		this.description.setMaxLength(200);
		this.description.setHint(Component.translatable("rewind.screen.description_hint"));
		this.addRenderableWidget(this.name);
		this.addRenderableWidget(this.description);
		this.addRenderableWidget(Button.builder(Component.translatable("rewind.screen.save"), b -> {
			ClientPlayNetworking.send(new RewindPayloads.Action(RewindPayloads.SAVE, this.name.getValue().trim(), this.description.getValue().trim()));
			this.name.setValue("");
			this.description.setValue("");
		}).bounds(x + 292, y + 23, 78, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).bounds(x + W / 2 - 50, y + this.h() - 26, 100, 20).build());
		ClientPlayNetworking.send(new RewindPayloads.Action(RewindPayloads.OPEN, "", ""));
	}

	@Override
	public void onClose() {
		ClientPlayNetworking.send(new RewindPayloads.Action(RewindPayloads.CLOSE, "", ""));
		Minecraft.getInstance().gui.setScreen(this.parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, this.width, this.height, 0xB0000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = this.left(), y = this.top();
		g.fill(x - 1, y - 1, x + W + 1, y + this.h() + 1, 0xFF505A66);
		g.fill(x, y, x + W, y + this.h(), 0xFF22262C);
		g.fill(x, y, x + W, y + 2, 0xFF7FD0A0);
		super.extractRenderState(g, mouseX, mouseY, a);
		RewindPayloads.ListReply l = latest;
		String head = this.title.getString() + (l == null ? "" : "  -  " + l.world());
		g.text(this.font, head, x + 10, y + 8, 0xFFE8ECF0, false);
		if (l == null) {
			g.text(this.font, Component.translatable("rewind.screen.waiting").getString(), x + 10, y + 56, 0xFFA0A8B0, false);
			return;
		}
		if (l.busy()) {
			String b = Component.translatable("rewind.screen.busy").getString();
			g.text(this.font, b, x + W - 10 - this.font.width(b), y + 8, 0xFFFFC040, false);
		}
		int ly = y + 50;
		g.fill(x + 8, ly - 2, x + W - 8, ly + this.rows() * ROW + 2, 0xFF181B20);
		if (l.entries().isEmpty()) {
			g.text(this.font, Component.translatable("rewind.screen.empty").getString(), x + 14, ly + 6, 0xFFA0A8B0, false);
		}
		SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		boolean armedValid = System.currentTimeMillis() - this.armedAt < 4000;
		for (int i = 0; i < this.rows() && i + this.scroll < l.entries().size(); i++) {
			RewindPayloads.Entry e = l.entries().get(i + this.scroll);
			int ry = ly + i * ROW;
			boolean hover = mouseX >= x + 8 && mouseX < x + W - 8 && mouseY >= ry && mouseY < ry + ROW;
			if (hover) {
				g.fill(x + 8, ry, x + W - 8, ry + ROW, 0xFF2A3038);
			}
			int color = switch (e.kind()) {
				case "auto" -> 0xFF80E0A0;
				case "before-restore" -> 0xFFF0D060;
				default -> 0xFFE8ECF0;
			};
			g.text(this.font, e.name(), x + 14, ry + 3, color, false);
			String sub = f.format(new Date(e.created())) + "  " + SnapshotStore.size(e.bytes()) + (e.description().isEmpty() ? "" : "  " + SnapshotStore.describe(e.kind(), e.description()).getString());
			g.pose().pushMatrix();
			g.pose().translate(x + 14, ry + 13);
			g.pose().scale(0.75F, 0.75F);
			g.text(this.font, this.font.plainSubstrByWidth(sub, 330), 0, 0, 0xFF8890A0, false);
			g.pose().popMatrix();
			if (l.canManage()) {
				boolean armR = armedValid && this.armed.equals("r:" + e.name());
				boolean armD = armedValid && this.armed.equals("d:" + e.name());
				this.button(g, x + W - 132, ry + 3, 62, armR ? "§c" + Component.translatable("rewind.screen.sure").getString()
						: Component.translatable("rewind.screen.restore").getString(), mouseX, mouseY, !l.busy());
				this.button(g, x + W - 66, ry + 3, 54, armD ? "§c" + Component.translatable("rewind.screen.sure").getString()
						: Component.translatable("rewind.screen.delete").getString(), mouseX, mouseY, !l.busy());
			}
		}
		if (l.entries().size() > this.rows()) {
			String s = (this.scroll + 1) + "-" + Math.min(l.entries().size(), this.scroll + this.rows()) + " / " + l.entries().size();
			g.text(this.font, s, x + W - 10 - this.font.width(s), y + this.h() - 40, 0xFF8890A0, false);
		}
		String auto = l.autoMinutes() > 0 ? Component.translatable("rewind.screen.auto_on", l.autoMinutes()).getString()
				: Component.translatable("rewind.screen.auto_off").getString();
		g.text(this.font, auto, x + 10, y + this.h() - 40, 0xFF8890A0, false);
		if (!l.canManage()) {
			g.text(this.font, Component.translatable("rewind.not_allowed").getString(), x + 10, y + 30, 0xFFE06060, false);
		}
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mx, int my, boolean enabled) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + 16;
		g.fill(x, y, x + w, y + 16, enabled ? (hover ? 0xFF5A646E : 0xFF404850) : 0xFF30343A);
		g.text(this.font, label, x + w / 2 - this.font.width(label.replace("§c", "")) / 2, y + 4, enabled ? 0xFFE8ECF0 : 0xFF707880, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RewindPayloads.ListReply l = latest;
		if (l != null && l.canManage() && !l.busy()) {
			int x = this.left(), ly = this.top() + 50;
			double mx = event.x(), my = event.y();
			for (int i = 0; i < this.rows() && i + this.scroll < l.entries().size(); i++) {
				RewindPayloads.Entry e = l.entries().get(i + this.scroll);
				int ry = ly + i * ROW + 3;
				if (my >= ry && my < ry + 16) {
					if (mx >= x + W - 132 && mx < x + W - 70) {
						this.click("r:" + e.name(), RewindPayloads.RESTORE, e.name());
						return true;
					}
					if (mx >= x + W - 66 && mx < x + W - 12) {
						this.click("d:" + e.name(), RewindPayloads.DELETE, e.name());
						return true;
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** First click arms the button ("Sure?"), a second one within four seconds does it. */
	private void click(String key, int action, String snapshot) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
		if (this.armed.equals(key) && System.currentTimeMillis() - this.armedAt < 4000) {
			this.armed = "";
			ClientPlayNetworking.send(new RewindPayloads.Action(action, snapshot, ""));
		} else {
			this.armed = key;
			this.armedAt = System.currentTimeMillis();
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		RewindPayloads.ListReply l = latest;
		if (l != null) {
			this.scroll = Math.clamp(this.scroll + (scrollY < 0 ? 1 : -1), 0, Math.max(0, l.entries().size() - this.rows()));
		}
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
