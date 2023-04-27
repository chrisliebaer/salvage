package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.command.InspectVolumeResponse;

public record SalvageVolume(String name, BackupMeta.VolumeMeta meta) {
	
	public static SalvageVolume fromInspectVolumeResponse(InspectVolumeResponse volume) {
		var name = volume.getName();
		var meta = BackupMeta.VolumeMeta.fromVolumeInspect(volume);
		
		return new SalvageVolume(name, meta);
	}
}
