package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.command.InspectContainerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

@Slf4j
public record SalvageConfiguration(String hostname, String ownContainerId, List<SalvageTide> tides, HashMap<String, SalvageCrane> cranes) {
	
	private static final String ENV_HOSTNAME = "MACHINE";
	
	private static final String LABEL_SALVAGE_TIDE_PREFIX = "salvage.tides.";
	private static final String LABEL_SALVAGE_CRANE_PREFIX = "salvage.cranes.";
	
	public static SalvageConfiguration fromContainerInspect(InspectContainerResponse container) {
		var labels = container.getConfig().getLabels();
		
		// load env config
		var hostname = System.getenv(ENV_HOSTNAME);
		if (hostname == null) {
			throw new IllegalArgumentException("tried to construct configuration, but no hostname was specified");
		}
		
		// index labels
		var tideNames = new HashSet<String>();
		var craneNames = new HashSet<String>();
		for (var key : labels.keySet()) {
			if (key.startsWith(LABEL_SALVAGE_TIDE_PREFIX)) {
				var tide = key.substring(LABEL_SALVAGE_TIDE_PREFIX.length());
				tide = tide.substring(0, tide.indexOf('.'));
				tideNames.add(tide);
			} else if (key.startsWith(LABEL_SALVAGE_CRANE_PREFIX)) {
				var crane = key.substring(LABEL_SALVAGE_CRANE_PREFIX.length());
				crane = crane.substring(0, crane.indexOf('.'));
				craneNames.add(crane);
			}
		}
		
		// load crane configs
		var cranes = new HashMap<String, SalvageCrane>();
		for (var craneName : craneNames) {
			var crane = SalvageCrane.fromLabels(craneName, LABEL_SALVAGE_CRANE_PREFIX + craneName, labels);
			cranes.put(craneName, crane);
			log.debug("loaded crane '{}'", crane);
		}
		
		// load tide configs
		var tides = new ArrayList<SalvageTide>();
		for (var tideName : tideNames) {
			var tide = SalvageTide.fromLabels(tideName, LABEL_SALVAGE_TIDE_PREFIX + tideName, labels, cranes);
			tides.add(tide);
			log.debug("loaded tide '{}'", tide);
		}
		
		if (tides.isEmpty()) {
			throw new IllegalArgumentException("tried to construct configuration, but no tides were specified");
		}
		
		return new SalvageConfiguration(hostname, container.getId(), tides, cranes);
	}
}
