package de.chrisliebaer.salvage.entity;

import java.util.HashMap;
import java.util.Map;

public record SalvageCrane(String name, String image, Map<String, String> env, Map<String, String> mounts, int maxConcurrent) {
	
	private static final String LABEL_SALVAGE_IMAGE_SUFFIX = ".image";
	private static final String LABEL_SALVAGE_ENV = ".env.";
	private static final String LABEL_SALVAGE_MOUNT = ".mount.";
	private static final String LABEL_SALVAGE_MAX_CONCURRENT = ".maxConcurrent";
	
	public static SalvageCrane fromLabels(String name, String prefix, Map<String, String> labels) {
		var image = labels.get(prefix + LABEL_SALVAGE_IMAGE_SUFFIX);
		if (image == null)
			throw new IllegalArgumentException("tried to construct crane '" + name + "', but no image was specified");
		
		var env = new HashMap<String, String>();
		var mounts = new HashMap<String, String>();
		
		for (var entry : labels.entrySet()) {
			var key = entry.getKey();
			var value = entry.getValue();
			
			if (key.startsWith(prefix + LABEL_SALVAGE_ENV)) {
				var envKey = key.substring(prefix.length() + LABEL_SALVAGE_ENV.length());
				env.put(envKey, value);
			} else if (key.startsWith(prefix + LABEL_SALVAGE_MOUNT)) {
				var mountKey = key.substring(prefix.length() + LABEL_SALVAGE_MOUNT.length());
				mounts.put(mountKey, value);
			}
		}
		
		var maxConcurrent = Integer.MAX_VALUE;
		try {
			maxConcurrent = Integer.parseInt(labels.get(prefix + LABEL_SALVAGE_MAX_CONCURRENT));
		} catch (NumberFormatException ignore) {}
		return new SalvageCrane(name, image, env, mounts, maxConcurrent);
	}
}
