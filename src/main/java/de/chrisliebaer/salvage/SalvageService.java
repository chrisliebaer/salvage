package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
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
import de.chrisliebaer.salvage.reporting.CaptainHook;
import de.chrisliebaer.salvage.reporting.FinishState;
import de.chrisliebaer.salvage.reporting.TideLog;
import de.chrisliebaer.salvage.reporting.VolumeLog;
import de.chrisliebaer.salvage.reporting.WebhookReporter;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Log4j2
public class SalvageService extends AbstractService {
	
	public static final String SALVAGE_ENTITY_LABEL = "salvage.entity";
	public static final String COMPOSE_LABEL_PROJECT = "com.docker.compose.project";
	private static final String COMPOSE_LABEL_VOLUME = "com.docker.compose.volume";
	
	private static final String ROOT_LABEL = "salvage.root";
	private static final String LABEL_CONTAINER_TIDE_MAP_PREFIX = "salvage.tide.";
	
	/**
	 * Amount of time we wait after a tide has been executed before we check for new tides. This is to prevent double execution for imprecise clocks.
	 */
	private static final int TIDE_CLOCK_WAIT = 5000;
	
	private SalvageConfiguration configuration;
	private final Thread serviceThread = new Thread(this::serviceThreadEntry, "SalvageService");
	private String ownContainerId;
	
	private final HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	
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
				try {
					verifyCraneImage(docker, crane);
				} catch (ImagePullFailedException e) {
					if (e.isPresent())
						log.warn("failed to check for crane '{}' image '{}', but old image is still present", crane.name(), crane.image(), e);
					else
						log.warn("failed to pull for crane '{}' image '{}', no existing image present, good luck", crane.name(), crane.image(), e);
				}
			}
		} catch (Throwable e) {
			notifyFailed(e);
			return;
		}
		
		notifyStarted();
		loop();
	}
	
	private void verifyCraneImage(DockerClient docker, SalvageCrane crane) throws InterruptedException, ImagePullFailedException {
		
		// check if image is already present
		boolean isPresent;
		try {
			var image = docker.inspectImageCmd(crane.image()).exec();
			isPresent = true;
		} catch (NotFoundException ignore) {
			isPresent = false;
		}
		
		if (!isPresent || crane.pullOnRun()) {
			
			log.info("fetching : '{}' for crane '{}' (isPresent: {}, pullOnRun: {})", crane.image(), crane.name(), isPresent, crane.pullOnRun());
			try {
				var callback = docker.pullImageCmd(crane.image()).exec(new PullImageResultCallback());
				callback.awaitCompletion();
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				throw new ImagePullFailedException(e, isPresent);
			}
		}
	}
	
	private void loop() {
		var tides = new ArrayList<NextTideExecution>();
		for (var tide : configuration.tides()) {
			tides.add(new NextTideExecution(tide, tide.nextExecution(ZonedDateTime.now())));
		}
		
		// empty tides should be picked up during configuration load, but we check again in case of code changes
		if (tides.isEmpty()) {
			notifyFailed(new IllegalStateException("no tides configured, nothing to do"));
			return;
		}
		
		while (!Thread.interrupted()) {
			tides.sort(Comparator.comparing(NextTideExecution::time));
			
			// remove first tide from list (will be added again after execution but with new execution time)
			var nextExecution = tides.removeFirst();
			var tide = nextExecution.tide();
			
			// sleep for next execution
			var duration = Duration.between(ZonedDateTime.now(), nextExecution.time());
			duration = duration.isNegative() ? Duration.ZERO : duration;
			
			log.info("waiting for next tide '{}' in '{}'", tide.name(), SalvageMain.formatDuration(duration));
			try {
				// to prevent double execution, we add 5 seconds to the duration
				Thread.sleep(Math.max(duration.toMillis(), 0) + TIDE_CLOCK_WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				continue;
			}
			
			ThreadContext.put("tide", tide.name());
			tideExceptionWrapped(tide, nextExecution.time().toInstant());
			ThreadContext.remove("tide");
			
			// add tide with next execution time to list
			tides.add(new NextTideExecution(tide, tide.nextExecution(ZonedDateTime.now())));
		}
		log.info("exiting salvage service thread");
		Thread.currentThread().interrupt();
		
		notifyStopped();
	}
	
	/**
	 * Wraps tide execution in handler logic for error reporting.
	 *
	 * @param tide          tide to execute
	 * @param nextExecution
	 */
	private void tideExceptionWrapped(SalvageTide tide, Instant nextExecution) {
		var hook = new WebhookReporter(tide.reportingUrlStore(), configuration.hostname(), httpClient);
		var tideLog = new TideLog(tide, hook);
		
		var start = Instant.now();
		try {
			executeTide(tide, nextExecution, tideLog);
		} catch (IOException e) {
			log.error("failed to execute tide '{}'", tide.name(), e);
			tideLog.failure(e);
		} catch (InterruptedException e) {
			log.warn("tide '{}' was interrupted, skipping reporting since JVM wants to shut down", tide.name());
			Thread.currentThread().interrupt();
			return;
		} catch (Throwable e) {
			log.error("unexpected error while executing tide '{}', please report this issue", tide.name(), e);
			tideLog.failure("fatal error, check logs and report issue");
		}
		
		doTideReporting(tideLog, hook);
	}
	
	private void executeTide(SalvageTide tide, Instant executionStart, TideLog tideLog) throws IOException, InterruptedException {
		log.info("executing tide '{}'", tide.name());
		tideLog.start();
		
		try (var docker = createDefaultClient()) {
			docker.pingCmd().exec();
			
			try {
				// crane might have new image or user purged existing image, so we check again
				verifyCraneImage(docker, tide.crane());
			} catch (ImagePullFailedException e) {
				if (e.isPresent()) {
					tideLog.failure("failed to pull crane '%s' image '%s' but local image is still present".formatted(tide.crane().name(), tide.crane().image()));
				} else {
					tideLog.failure("failed to pull crane '%s' image '%s' no local image available".formatted(tide.crane().name(), tide.crane().image()));
					return;
				}
			}
			
			// identifying volumes of tide is rather complicated and involes different logic, depending on wether the volume is part of a project or not
			var volumes = getVolumeNamesForTide(docker, tide);
			
			log.info("found {} volumes belonging to tide '{}'", volumes.size(), tide.name());
			if (log.isDebugEnabled()) {
				for (var volume : volumes.values()) {
					log.debug("\t- found volume {}", volume.name());
				}
			}
			
			// identify container depending on these volumes
			var containers = docker.listContainersCmd()
					.withFilter("volume", volumes.keySet()).exec().stream()
					.map(c -> docker.inspectContainerCmd(c.getId()).exec())
					.map(c -> SalvageContainer.fromContainer(c, volumes))
					.collect(Collectors.toList());
			
			// remove ourselves, since we never want to touch our own container
			containers.removeIf(c -> c.id().equals(ownContainerId));
			
			log.info("found {} containers depending on tide '{}'", containers.size(), tide.name());
			if (log.isDebugEnabled()) {
				for (var container : containers) {
					log.debug("\t- found container {}", container.name());
				}
			}
			
			// group tide into waves to minimize downtime
			var groups = BackupGrouping.groups(containers, volumes, tide.groupingMode());
			log.debug("grouping tide into {} waves", groups.size());
			if (log.isDebugEnabled()) {
				for (int i = 0; i < groups.size(); i++) {
					var group = groups.get(i);
					log.debug("\t- group no. {} with {} containers and {} volumes:", i, group.containers().size(), group.volumes().size());
					for (var container : group.containers())
						log.trace("\t\t- container {}", container.name());
					for (var volume : group.volumes())
						log.trace("\t\t- volume {}", volume.name());
				}
			}
			
			var hostMeta = new BackupMeta.HostMeta(System.currentTimeMillis(), executionStart.toEpochMilli(), configuration.hostname());
			
			// instance worker pool for backup, which can be reused for all groups
			try (var operation = new BackupOperation(docker, tide.maxConcurrent(), configuration.cranes().values(), hostMeta, tideLog)) {
				// backup each group individually but in series
				for (int i = 0; i < groups.size(); i++) {
					BackupGrouping.Group group = groups.get(i);
					log.debug("starting backup of group no. {} with {} containers and {} volumes", i, group.containers().size(), group.volumes().size());
					
					// prepare containers for backup using transaction tracking to provide the best effort in restoring container state in all circumstances
					try (var transaction = new StateTransaction(docker)) {
						backupGroup(tide, operation, group, transaction);
						
						// TODO if interrupted abort tide, probably should cancel vessel as well
					}
					
					log.debug("finish backup of group no. {} with {} containers and {} volumes", i, group.containers().size(), group.volumes().size());
				}
			}
		}
		
		// if crane image lookup failed earlier, we don't want to report success
		if (tideLog.tideResult().state() != FinishState.FAILURE)
			tideLog.success();
	}
	
	private static void doTideReporting(TideLog tideLog, CaptainHook hook) {
		var tideResult = tideLog.tideResult();
		
		// volume list will not always be present, depending on the reason the tide failed, logging needs to be aware of that
		var volumes = tideLog.volumeLogs().stream().map(VolumeLog::volume).toList();
		
		if (tideResult.state() == FinishState.SUCCESS) {
			if (volumes.isEmpty()) {
				log.info("tide '{}' successfully finished, but no volumes are assigned to tide", tideLog.tide().name());
			} else {
				var volumesStr = volumes.stream().map(SalvageVolume::name).collect(Collectors.joining(", "));
				log.info("tide '{}' successfully finished back up volumes: {}", tideLog.tide().name(), volumesStr);
			}
			
			hook.reportTideSuccess(tideLog.tide(), volumes, tideLog.stopWatch().duration());
			return;
		}
		
		// tide encountered issues, check how many volumes were successfully backed up
		var successfulVolumes = tideLog.volumeLogs().stream().filter(v -> v.state() == FinishState.SUCCESS).map(VolumeLog::volume).toList();
		var failedVolumes = tideLog.volumeLogs().stream().filter(v -> v.state() == FinishState.FAILURE).map(VolumeLog::volume).toList();
		
		if (!failedVolumes.isEmpty() || !successfulVolumes.isEmpty()) {
			// due to how tide volumes are requested, if there is at least one volume, we can assume that all volumes are present
			var successfulVolumesStr = successfulVolumes.stream().map(SalvageVolume::name).collect(Collectors.joining(", "));
			var failedVolumesStr = failedVolumes.stream().map(SalvageVolume::name).collect(Collectors.joining(", "));
			log.error(
					"tide '{}' failed partially. successfully backed up volumes: {}, failed volumes: {} (reason: {})",
					tideLog.tide().name(),
					successfulVolumesStr,
					failedVolumesStr,
					tideResult.message());
			hook.reportTideFailure(tideLog.tide(), successfulVolumes, failedVolumes, tideResult.message(), tideLog.stopWatch().duration());
		} else {
			// tide failed before any volumes were indexed
			log.error("tide '{}' failed (reason: {})", tideLog.tide().name(), tideResult.message());
			hook.reportTideFailure(tideLog.tide(), tideResult.message(), tideLog.stopWatch().duration());
		}
		
		// report for individual volumes is done in the volume log itself in order to have them closer to the actual time the volume was backed up
	}
	
	private static void backupGroup(SalvageTide tide, BackupOperation operation, BackupGrouping.Group group, StateTransaction transaction) {
		
		var containers = group.containers();
		
		// if an error occurs during preparation, we can simply abort the whole backup
		try {
			for (var container : containers) {
				ThreadContext.put("container", container.name());
				log.debug("preparing container {} for backup", container.name());
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
				ThreadContext.put("container", container.name());
				log.debug("restoring container {} to previous state", container.name());
				transaction.restore(container);
			} catch (Throwable e) {
				log.warn("failed to restore post backup state for tide '{}' and container '{}'", tide.name(), container.name(), e);
			} finally {
				ThreadContext.remove("container");
			}
		}
	}
	
	private static DockerClient createDefaultClient() {
		var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
		DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
				.dockerHost(config.getDockerHost())
				.build();
		return DockerClientImpl.getInstance(config, httpClient);
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
	
	private static Map<String, SalvageVolume> getVolumeNamesForTide(DockerClient docker, SalvageTide tide) {
		var tideLabel = LABEL_CONTAINER_TIDE_MAP_PREFIX + tide.name();
		var map = new HashMap<String, SalvageVolume>();
		
		// check for volumes that are mapped to tide via container labels
		var containers = docker.listContainersCmd()
				.withLabelFilter(List.of(tideLabel))
				.withShowAll(true)
				.exec();
		
		// resolve volume names in respect to container compose project
		for (var container : containers) {
			var labels = container.getLabels();
			var volumeNames = labels.get(tideLabel).split(",");
			log.trace("container '{}' is mapping volumes to tide '{}' via labels: {}", container.getId(), tide.name(), volumeNames);
			
			var project = labels.get(COMPOSE_LABEL_PROJECT);
			if (project == null) {
				log.warn("container '{}' is not part of a project, only project containers can be used for volume mapping", container.getId());
				continue;
			}
			
			for (var volumeName : volumeNames) {
				InspectVolumeResponse volume;
				if (volumeName.startsWith("g:")) {
					// perform global lookup using raw volume name
					var globalName = volumeName.substring(2);
					volume = docker.inspectVolumeCmd(globalName).exec();
				} else {
					log.trace("performing lookup volume '{}' in compose project '{}'", volumeName, project);
					var volumes = docker.listVolumesCmd()
							.withFilter("label", List.of(
									COMPOSE_LABEL_PROJECT + "=" + project,
									COMPOSE_LABEL_VOLUME + "=" + volumeName
							))
							.exec().getVolumes();
					
					if (volumes.size() != 1) {
						throw new IllegalArgumentException("expected exactly one volume in project '" + project + "' named '" + volumeName + "' but found " + volumes.size());
					}
					
					volume = volumes.getFirst();
				}
				
				log.trace("successfully identified volume '{}' as docker volume '{}'", volumeName, volume.getName());
				
				map.put(volume.getName(), SalvageVolume.fromInspectVolumeResponse(volume));
			}
		}
		return map;
	}
	
	/**
	 * Calls docker API to get own container ID.
	 *
	 * @param docker the docker connection.
	 * @return The container id of the container this application is currently running in.
	 * @throws IllegalStateException If fetching the container id failed.
	 */
	private static String getOwnContainerId(DockerClient docker) {
		
		// we also check for stopped container since there is no situation where these are a good idea
		var containers = docker.listContainersCmd()
				.withShowAll(true)
				.withLabelFilter(List.of(ROOT_LABEL))
				.exec();
		
		if (containers.isEmpty())
			throw new IllegalStateException("no container with label '" + ROOT_LABEL + "' found");
		if (containers.size() > 1)
			throw new IllegalStateException("multiple containers with label '" + ROOT_LABEL + "' found, check if older containers exist");
		
		return containers.getFirst().getId();
	}
	
	private record NextTideExecution(SalvageTide tide, ZonedDateTime time) {}
	
	private static final class ImagePullFailedException extends Exception {
		
		@Getter private final boolean isPresent;
		
		private ImagePullFailedException(Throwable cause, boolean isPresent) {
			super(cause);
			this.isPresent = isPresent;
		}
	}
}
