package de.chrisliebaer.salvage;

import com.cronutils.model.time.ExecutionTime;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.common.util.concurrent.AbstractService;
import de.chrisliebaer.salvage.entity.BackupMeta;
import de.chrisliebaer.salvage.entity.SalvageConfiguration;
import de.chrisliebaer.salvage.entity.SalvageContainer;
import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageTide;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import de.chrisliebaer.salvage.grouping.BackupGrouping;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Log4j2
public class SalvageService extends AbstractService {
	
	public static final String SALVAGE_ENTITY_LABEL = "salvage.entity";
	
	private static final String ROOT_LABEL = "salvage.root";
	private static final String LABEL_VOLUME_TIDE_NAME = "salvage.tide";
	
	private SalvageConfiguration configuration;
	private final Thread serviceThread = new Thread(this::serviceThreadEntry, "SalvageService");
	private String ownContainerId;
	
	@Override
	protected void doStart() {
		serviceThread.start();
	}
	
	@Override
	protected void doStop() {
		serviceThread.interrupt();
	}
	
	private void serviceThreadEntry() {
		try (var docker = createDefaultClient()) {
			docker.pingCmd().exec();
			
			cleanupLeftOver(docker);
			
			ownContainerId = getOwnContainerId(docker);
			InspectContainerResponse ownContainer;
			log.info("found own container id as {}", ownContainerId);
			try {
				ownContainer = docker.inspectContainerCmd(ownContainerId).exec();
			} catch (NotFoundException e) {
				notifyFailed(new IllegalStateException("failed to request own container from docker daemon", e));
				return;
			}
			
			configuration = SalvageConfiguration.fromContainerInspect(ownContainer);
			
			// ensure we have all images specified by cranes
			for (var crane : configuration.cranes().values()) {
				verifyCraneImage(docker, crane);
			}
		} catch (Throwable e) {
			notifyFailed(e);
			return;
		}
		
		notifyStarted();
		loop();
	}
	
	private void verifyCraneImage(DockerClient docker, SalvageCrane crane) throws InterruptedException {
		var images = docker.listImagesCmd().withImageNameFilter(crane.image()).exec();
		if (!images.isEmpty())
			return;
		
		log.info("missing image '{}' for crane '{}', pulling it now", crane.image(), crane.name());
		var callback = docker.pullImageCmd(crane.image()).exec(new PullImageResultCallback());
		try {
			callback.awaitCompletion();
		} catch (NotFoundException e) {
			throw new IllegalStateException("failed to pull image '" + crane.image() + "' for crane '" + crane.name() + "'", e);
		}
	}
	
	private void loop() {
		
		while (!Thread.interrupted()) {
			
			var maybeTide = getNextExecutionTide();
			if (maybeTide.isEmpty()) {
				log.warn("no tide specifies future execution time, nothing to do");
				break;
			}
			
			var tide = maybeTide.get();
			var maybeDuration = ExecutionTime.forCron(tide.cron()).timeToNextExecution(ZonedDateTime.now());
			if (maybeDuration.isEmpty()) {
				// race condition, if last execution just passed us, retry
				continue;
			}
			
			// sleep for next execution
			var duration = maybeDuration.get();
			log.info("waiting for next tide '{}' in '{}'", tide.name(), formatDuration(duration));
			try {
				Thread.sleep(duration.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				continue;
			}
			
			ThreadContext.put("tide", tide.name());
			try {
				executeTide(tide);
			} catch (IOException e) {
				log.error("failed to execute tide '{}'", tide.name(), e);
			}
			ThreadContext.remove("tide");
		}
		log.info("exiting salvage service thread");
		Thread.currentThread().interrupt();
		
		notifyStopped();
	}
	
	private void executeTide(SalvageTide tide) throws IOException {
		log.info("executing tide '{}'", tide.name());
		
		try (var docker = createDefaultClient()) {
			docker.pingCmd().exec();
			
			// identify volumes belonging to this tide
			var volumes = docker.listVolumesCmd()
					.withFilter("label", List.of(LABEL_VOLUME_TIDE_NAME + "=" + tide.name())).exec().getVolumes().stream()
					.map(v -> SalvageVolume.fromInspectVolumeResponse(v, configuration.cranes()))
					.collect(Collectors.toMap(SalvageVolume::name, v -> v));
			
			log.info("found {} volumes belonging to tide '{}'", volumes.size(), tide.name());
			if (log.isDebugEnabled()) {
				for (var volume : volumes.values()) {
					log.debug("\t- found volume {}", volume.name());
				}
			}
			
			// identify container depending to these volumes
			var containers = docker.listContainersCmd()
					.withFilter("volume", volumes.keySet()).exec().stream()
					.map(c -> docker.inspectContainerCmd(c.getId()).exec())
					.map(c -> SalvageContainer.fromContainer(c, volumes))
					.collect(Collectors.toList());
			
			// remove ourself, since we never want to touch our own container (only happens if user is actually stupid)
			containers.removeIf(c -> c.id().equals(ownContainerId));
			
			log.info("found {} containers depending on tide '{}'", containers.size(), tide.name());
			if (log.isDebugEnabled()) {
				for (var container : containers) {
					log.debug("\t- found container {}", container.id());
				}
			}
			
			// group tide into waves to minimize downtime
			var groups = BackupGrouping.groups(containers, volumes, tide.groupingMode());
			log.info("grouping tide into {} waves", groups.size());
			if (log.isDebugEnabled()) {
				for (int i = 0; i < groups.size(); i++) {
					var group = groups.get(i);
					log.debug("\t- group no. {} with {} containers and {} volumes:", i, group.containers().size(), group.volumes().size());
					for (var container : group.containers())
						log.trace("\t\t- container {}", container.id());
					for (var volume : group.volumes())
						log.trace("\t\t- volume {}", volume.name());
				}
			}
			
			// instance worker pool for backup, which can be reused for all groups
			var hostMeta = new BackupMeta.HostMeta(System.currentTimeMillis(), configuration.hostname());
			var operation = new BackupOperation(docker, tide.maxConcurrent(), configuration.cranes().values(), hostMeta);
			
			// backup each group individually but in series
			for (int i = 0; i < groups.size(); i++) {
				BackupGrouping.Group group = groups.get(i);
				log.info("starting backup of group no. {} with {} containers and {} volumes", i, group.containers().size(), group.volumes().size());
				
				// prepare containers for backup using transaction tracking to provide best effort in restoring container state in all circumstances
				try (var transaction = new StateTransaction(docker)) {
					backupGroup(tide, operation, group, transaction);
					
					// TODO if interrupted abort tide
				}
				
				log.info("finish backup of group no. {} with {} containers and {} volumes", i, group.containers().size(), group.volumes().size());
			}
		}
	}
	
	private static void backupGroup(SalvageTide tide, BackupOperation operation, BackupGrouping.Group group, StateTransaction transaction) {
		
		var containers = group.containers();
		
		// if an error occurs during preperation we can simply abort the whole backup
		try {
			for (var container : containers) {
				ThreadContext.put("container", container.id());
				log.debug("preparing container {} for backup", container.id());
				transaction.prepare(container);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("failed to establish pre backup state for tide '" + tide.name() + "'", e);
		} catch (Throwable e) {
			throw new IllegalStateException("failed to establish pre backup state for tide '" + tide.name() + "'", e);
		} finally {
			ThreadContext.remove("container");
		}
		
		// errors during backup operation can not be recovered, we will continue with the backup and hope for the best
		try {
			operation.backupVolumes(tide.crane(), group.volumes());
		} catch (Throwable e) {
			log.error("encountered error during backup of tide '{}'", tide.name(), e);
			Thread.currentThread().interrupt();
		}
		
		// error during finish state on containers need to be ignored, since we might be able to recover some containers
		for (var container : containers) {
			try {
				ThreadContext.put("container", container.id());
				log.debug("restoring container {} to previous state", container.id());
				transaction.restore(container);
			} catch (Throwable e) {
				log.warn("failed to restore post backup state for tide '{}' and container '{}'", tide.name(), container.id(), e);
			} finally {
				ThreadContext.remove("container");
			}
		}
	}
	
	private Optional<SalvageTide> getNextExecutionTide() {
		Optional<Duration> nextDuration = Optional.empty();
		SalvageTide nextTide = null;
		
		// find tide with closest execution time
		var tides = configuration.tides();
		for (var tide : tides) {
			var next = ExecutionTime.forCron(tide.cron());
			var maybeDuration = next.timeToNextExecution(ZonedDateTime.now());
			if (maybeDuration.isEmpty()) {
				log.warn("tide does not contain any future executions: {}", tide.cron().asString());
				continue;
			}
			
			var duration = maybeDuration.get();
			if (nextDuration.isPresent()) {
				if (duration.compareTo(nextDuration.get()) < 0) {
					nextDuration = Optional.of(duration);
					nextTide = tide;
				}
			} else {
				nextDuration = Optional.of(duration);
				nextTide = tide;
			}
		}
		
		var finalNextTide = nextTide;
		return nextDuration.map(duration -> finalNextTide);
	}
	
	private static DockerClient createDefaultClient() {
		var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
		DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
				.dockerHost(config.getDockerHost())
				.build();
		return DockerClientImpl.getInstance(config, httpClient);
	}
	
	private static String formatDuration(Duration duration) {
		// https://stackoverflow.com/a/40487511/1834100
		return duration.toString()
				.substring(2)
				.replaceAll("(\\d[HMS])(?!$)", "$1 ")
				.toLowerCase();
	}
	
	private static void cleanupLeftOver(DockerClient docker) {
		var list = docker.listContainersCmd()
				.withShowAll(true)
				.withLabelFilter(List.of(SALVAGE_ENTITY_LABEL))
				.exec();
		
		if (!list.isEmpty()) {
			log.info("found {} leftover containers from previous runs, cleaning up", list.size());
			for (var container : list) {
				log.debug("removing leftover container {}", container.getId());
				docker.removeContainerCmd(container.getId())
						.withForce(true)
						.exec();
			}
		}
	}
	
	/**
	 * Calls docker API to get own container ID.
	 *
	 * @param docker the docker connection.
	 * @return The container id of the container this application is currently running in.
	 * @throws IllegalStateException If fetching the container id failed.
	 */
	public static String getOwnContainerId(DockerClient docker) {
		
		// we also check of stopped container since there is no situation where these are a good idea
		var containers = docker.listContainersCmd()
				.withShowAll(true)
				.withLabelFilter(List.of(ROOT_LABEL))
				.exec();
		
		if (containers.isEmpty())
			throw new IllegalStateException("no container with label '" + ROOT_LABEL + "' found");
		if (containers.size() > 1)
			throw new IllegalStateException("multiple containers with label '" + ROOT_LABEL + "' found, check if you have older containers existing");
		
		return containers.get(0).getId();
	}
}
