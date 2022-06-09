package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.command.InspectVolumeResponse;

import java.util.Map;
import java.util.Optional;

public record SalvageVolume(String name, Optional<SalvageCrane> crane, boolean dryRun, BackupMeta.VolumeMeta meta) {
	
	private static final String LABEL_VOLUME_TIDE_NAME = "salvage.tide";
	private static final String LABEL_VOLUME_TIDE_DRY_RUN = "salvage.dryRun";
	
	public static SalvageVolume fromInspectVolumeResponse(InspectVolumeResponse volume, Map<String, SalvageCrane> cranes) {
		var name = volume.getName();
		var crane = cranes.get(volume.getLabels().get(LABEL_VOLUME_TIDE_NAME));
		var dryRun = Boolean.parseBoolean(volume.getLabels().get(LABEL_VOLUME_TIDE_DRY_RUN));
		var meta = BackupMeta.VolumeMeta.fromVolumeInspect(volume);
		
		return new SalvageVolume(name, Optional.ofNullable(crane), dryRun, meta);
	}
}
