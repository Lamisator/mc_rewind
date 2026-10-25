package net.antwire.rewind.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

/**
 * Picks the game language from the system language, once per game directory: a German system
 * (de_DE, de_AT, de_CH, ...) gets de_de, everything else stays English. Every mod of the Funkstadt
 * pack carries a copy of this class; the first one to run writes config/auto-language.done and the
 * others leave the choice alone, so a language the player picks later in the options sticks.
 * Call {@link #apply()} first thing in onInitializeClient.
 */
public final class AutoLanguage {
	private static final String GERMAN = "de_de";
	private static boolean done;

	private AutoLanguage() {}

	public static void apply() {
		if (done) return;
		done = true;
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path marker = gameDir.resolve("config").resolve("auto-language.done");
		if (Files.exists(marker)) return;
		try {
			if ("de".equalsIgnoreCase(Locale.getDefault().getLanguage())) switchToGerman(gameDir);
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, "Game language was set from the system language (" + Locale.getDefault()
					+ ") once. Delete this file to do it again.\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			// a failed language switch must never stop the game from starting
		}
	}

	private static void switchToGerman(Path gameDir) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.options != null) {
			// options.txt is already loaded: change it in memory and save once the game is up
			if (!"en_us".equals(mc.options.languageCode)) return;
			mc.options.languageCode = GERMAN;
			ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
				client.getLanguageManager().setSelected(GERMAN);
				client.options.save();
				client.reloadResourcePacks();
			});
			return;
		}
		// usual case: Fabric runs client initializers before vanilla reads options.txt, so edit the file
		Path options = gameDir.resolve("options.txt");
		if (!Files.exists(options)) {
			Files.writeString(options, "version:" + SharedConstants.getCurrentVersion().dataVersion().version()
					+ "\nlang:" + GERMAN + "\n", StandardCharsets.UTF_8);
			return;
		}
		List<String> lines = new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8));
		boolean found = false;
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).startsWith("lang:")) continue;
			found = true;
			if (!lines.get(i).equals("lang:en_us")) return; // the player chose a language: keep it
			lines.set(i, "lang:" + GERMAN);
		}
		if (!found) lines.add("lang:" + GERMAN);
		Files.write(options, lines, StandardCharsets.UTF_8);
	}
}
