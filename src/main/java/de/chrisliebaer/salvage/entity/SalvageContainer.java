package de.chrisliebaer.salvage.entity;

import com.github.dockerjava.api.command.InspectContainerResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SalvageContainer(String id, Optional<String> project, List<SalvageVolume> volumes,
							   ContainerAction action, Optional<ContainerCommand> commandPre, Optional<ContainerCommand> commandPost) {
	
	public static final String COMPOSE_LABEL_PROJECT = "com.docker.compose.project";
	
	private static final String LABEL_CONTAINER_ACTION = "salvage.action";
	
	private static final String LABEL_CONTAINER_COMMAND_USER = "salvage.command.user";
	private static final String LABEL_CONTAINER_COMMAND_PRE = "salvage.command.pre";
	private static final String LABEL_CONTAINER_COMMAND_POST = "salvage.command.post";
	
	/**
	 * Describes the action to be performed on the container in preparation for the backup.
	 */
	public enum ContainerAction {
		/**
		 * Do not alter container state during backup (but still run pre- and post-commands).
		 */
		IGNORE,
		
		/**
		 * Stop container during backup.
		 */
		STOP,
		
		/**
		 * Pause container during backup.
		 */
		PAUSE;
		
		public static ContainerAction fromString(String action) {
			return switch (action) {
				case "ignore" -> IGNORE;
				case "stop" -> STOP;
				case "pause" -> PAUSE;
				default -> throw new IllegalArgumentException("Unknown container action: " + action);
			};
		}
	}
	
	public static SalvageContainer fromContainer(InspectContainerResponse container, Map<String, SalvageVolume> volumes) {
		var usedVolumes = new ArrayList<SalvageVolume>();
		var labels = container.getConfig().getLabels();
		
		// container might be part of compose project
		var project = Optional.ofNullable(labels.get(COMPOSE_LABEL_PROJECT));
		
		// parse user or fall back to container user
		var user = labels.getOrDefault(LABEL_CONTAINER_COMMAND_USER, container.getConfig().getUser());
		
		// parse pre- and post-commands, if present
		var preCommand = Optional.ofNullable(labels.get(LABEL_CONTAINER_COMMAND_PRE))
				.map(s -> new ContainerCommand(s, user));
		var postCommand = Optional.ofNullable(labels.get(LABEL_CONTAINER_COMMAND_POST))
				.map(s -> new ContainerCommand(s, user));
		
		// set default action depending on whether pre- or post-commands are present
		var action = preCommand.isPresent() || postCommand.isPresent() ? ContainerAction.IGNORE : ContainerAction.STOP;
		
		// parse action override, if present
		action = Optional.ofNullable(labels.get(LABEL_CONTAINER_ACTION)).map(ContainerAction::fromString).orElse(action);
		
		// note: not all used volumes might be part of tide
		for (var mount : container.getMounts()) {
			var volume = volumes.get(mount.getName());
			if (volume != null)
				usedVolumes.add(volume);
		}
		
		return new SalvageContainer(container.getId(), project, usedVolumes, action, preCommand, postCommand);
	}
}
