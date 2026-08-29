package io.github.kxnar.btaanywhere.mod.hosting;

import java.nio.file.Path;
import java.util.List;

public record ModMirrorResult(List<String> copiedModIds, List<String> guestRequiredMods, Path guestManifest) {
	public ModMirrorResult {
		copiedModIds = List.copyOf(copiedModIds);
		guestRequiredMods = List.copyOf(guestRequiredMods);
		guestManifest = guestManifest.toAbsolutePath().normalize();
	}
}
