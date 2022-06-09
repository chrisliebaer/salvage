package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import de.chrisliebaer.salvage.entity.SalvageContainer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * This class is responsible for changing and maintaining the state of containers during backups. It implements AutoCloseable to be able roll back the state of containers
 * in all cases.
 */
@Slf4j
@AllArgsConstructor
public class StateTransaction implements AutoCloseable {
	
	/**
	 * Number of times to try waiting on container to reach stable state.
	 */
	private static final int RETRY_COUNT = 3;
	
	/**
	 * Number of milliseconds to wait between retries.
	 */
	private static final int RETRY_DELAY = 5000;
	
	private final DockerClient docker;
	private final Map<SalvageContainer, AffectedContainer> affectedContainers = new IdentityHashMap<>();
	
	@Override
	public void close() {
		if (affectedContainers.isEmpty())
			return;
		
		log.warn("found {} containers in backup state, assuming failure and rolling back container state", affectedContainers.size());
		for (var entry : affectedContainers.entrySet()) {
			var container = entry.getKey();
			var state = entry.getValue();
			log.debug("rolling back container {}", container.id());
			try {
				restore(container);
			} catch (Throwable e) {
				throw new IllegalStateException("failed to roll back container state for container " + container.id(), e);
			}
		}
	}
	
	public void prepare(SalvageContainer container) throws InterruptedException {
		var remainingRetries = RETRY_COUNT;
		
		InspectContainerResponse inspect;
		do {
			// if container is restarting, give it some time to finish
			inspect = docker.inspectContainerCmd(container.id()).exec();
			var state = inspect.getState();
			if (state.getRestarting()) {
				log.debug("container {} is restarting, waiting {}ms ({} tries remaining)", container.id(), RETRY_DELAY, remainingRetries);
				Thread.sleep(RETRY_DELAY);
			} else {
				break;
			}
			
		} while (remainingRetries-- > 0 && (inspect.getState().getRestarting()));
		var state = inspect.getState();
		
		// abort, rather than perform backup with container in unknown state
		if (state.getRestarting()) {
			throw new IllegalStateException("container '" + container.id() + "' has not reached stable state after " + RETRY_COUNT + " retries");
		}
		
		// run preperation command if container has one and is running (not paused)
		boolean preCommandRun = false;
		if (container.commandPre().isPresent() && state.getRunning() && !state.getPaused()) {
			var command = container.commandPre().get();
			log.debug("running preperation command '{}' on container {}", command, container.id());
			try {
				command.run(docker, container);
			} catch (Throwable e) {
				throw new IllegalStateException("preperation command '" + command + "' failed on container '" + container.id() + "'", e);
			}
			
			preCommandRun = true;
		}
		
		
		RestoreFunction restoreFn = (d, c) -> {
			// default: do nothing
		};
		
		// alter container state, if necessary
		switch (container.action()) {
			case IGNORE -> log.debug("container {} has no action, skipping", container.id());
			case STOP -> {
				// container must ne running and not paused, if it's not running at all, there is no need to stop it (but we must not start it again)
				if (state.getRunning()) {
					if (state.getPaused()) {
						throw new IllegalStateException("container '" + container.id() + "' is paused, cannot stop");
					}
					log.debug("stopping container {}", container.id());
					docker.stopContainerCmd(container.id()).exec();
					
					restoreFn = (d, c) -> {
						log.debug("starting container {}", c.id());
						d.startContainerCmd(c.id()).exec();
					};
				}
			}
			case PAUSE -> {
				// if container is running, we need to pause it (otherwise we don't need to do anything)
				if (state.getRunning() && !state.getPaused()) {
					log.debug("pausing container {}", container.id());
					docker.pauseContainerCmd(container.id()).exec();
					
					restoreFn = (d, c) -> {
						log.debug("unpausing container {}", c.id());
						d.unpauseContainerCmd(c.id()).exec();
					};
				}
			}
		}
		
		// add container to tracking list, so we can perform rollback if necessary
		affectedContainers.put(container, new AffectedContainer(restoreFn, preCommandRun));
	}
	
	public void restore(SalvageContainer container) throws Throwable {
		var affected = affectedContainers.remove(container);
		affected.restoreFn().run(docker, container);
		
		if (affected.preCommandRun() && container.commandPost().isPresent()) {
			var command = container.commandPost().get();
			log.debug("running post command '{}' on container {}", command, container.id());
			command.run(docker, container);
		}
	}
	
	/**
	 * This class is used to store the dynamic restore function for a container, depending on the action that was performed and which state it was in before.
	 *
	 * @param restoreFn the restore function.
	 * @param preCommandRun whether the preperation command was run, meaning that we also need to run the post command.
	 */
	private record AffectedContainer(RestoreFunction restoreFn, boolean preCommandRun) {}
	
	/**
	 * This interface is responsible for restoring the state of a container after a backup.
	 */
	private interface RestoreFunction {
		
		void run(DockerClient docker, SalvageContainer container) throws Throwable;
	}
}
