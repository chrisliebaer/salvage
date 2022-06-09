package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.command.InspectVolumeResponse;

import java.util.Map;

/**
 * This class stores meta about the volume being backed up. It is used by salvage to recreate the volume on restore and should be treated as an implementation detail by
 * the crane image.
 */

public record BackupMeta(HostMeta hostMeta, VolumeMeta volumeMeta, String crane, String image) {
	
	public record HostMeta(long timestamp, String host) {
	
	}
	
	public record VolumeMeta(String name, Map<String, String> labels, String driver, Map<String, String> driverOptions) {
		
		public static VolumeMeta fromVolumeInspect(InspectVolumeResponse inspect) {
			return new VolumeMeta(inspect.getName(), inspect.getLabels(), inspect.getDriver(), inspect.getOptions());
		}
	}
}
