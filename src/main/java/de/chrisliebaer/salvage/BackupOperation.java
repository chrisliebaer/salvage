package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import de.chrisliebaer.salvage.entity.BackupMeta;
import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import de.chrisliebaer.salvage.reporting.TideLog;
import de.chrisliebaer.salvage.reporting.VolumeLog;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.ThreadContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;

@Slf4j
public class BackupOperation implements AutoCloseable {
	
	private final Object lock = new Object();
	
	private final DockerClient docker;
	private final ExecutorService executor;
	private final Map<SalvageCrane, CranePool> cranes;
	private final BackupMeta.HostMeta hostMeta;
	private final TideLog tideLog;
	
	public BackupOperation(DockerClient docker, int maxConcurrent, Collection<SalvageCrane> cranes, BackupMeta.HostMeta hostMeta, TideLog tideLog) {
		this.docker = docker;
		this.hostMeta = hostMeta;
		this.tideLog = tideLog;
		
		// each worker will use its own docker client, we cant fully prevent networks errors, so later code needs to handle unexpected loss of connection to docker
		executor = Executors.newFixedThreadPool(maxConcurrent, new ThreadFactory() {
			private int counter;
			
			@Override
			public Thread newThread(Runnable r) {
				var t = new Thread(r, "CraneShip" + counter++);
				t.setDaemon(true);
				t.setUncaughtExceptionHandler((t1, e) -> log.error("fatal uncaught exception in thread {}", t1.getName(), e));
				return t;
			}
		});
		
		// create pool for each crane to pull tickets from
		this.cranes = new IdentityHashMap<>(cranes.size());
		for (SalvageCrane crane : cranes)
			this.cranes.put(crane, CranePool.fromCrane(crane));
	}
	
	@Override
	public void close() {
		executor.shutdown();
	}
	
	public void backupVolumes(SalvageCrane crane, Collection<SalvageVolume> volumes) {
		var remaining = new ArrayList<>(volumes);
		ArrayList<Future<Void>> futures = new ArrayList<>();
		var completionService = new ExecutorCompletionService<Void>(executor);
		var taskCounter = 0;
		while (!remaining.isEmpty()) {
			
			boolean craneFound = false;
			
			// match cranes to volumes, if available, otherwise wait for crane to become available again
			var it = remaining.iterator();
			
			// lock around iteration over cranes, since worker might return crane we already checked (makes semaphore rather pointless, but whatever)
			synchronized (lock) {
				while (it.hasNext()) {
					var volume = it.next();
					
					// check for crane availability
					var semaphore = cranes.get(crane).semaphore;
					if (semaphore.tryAcquire()) {
						
						// crane found, remove volume from remaining list and submit backup task
						it.remove();
						var volumeLog = tideLog.getVolumeLog(volume, crane);
						log.trace("found instance crane '{}' for volume '{}', deploying", crane.name(), volume.name());
						var future = completionService.submit(() -> {
							try {
								ThreadContext.put("volume", volume.name());
								log.info("starting backup for volume '{}' on crane '{}'", volume.name(), crane.name());
								backupVolume(volume, crane, volumeLog);
							} finally {
								log.trace("returning crane '{}' to pool", crane.name());
								synchronized (lock) {
									semaphore.release();
									lock.notifyAll();
								}
								ThreadContext.remove("volume");
							}
							return null;
						});
						taskCounter++;
						futures.add(future);
					}
				}
				
				// if no crane was found for a volume, wait for a crane to become available again
				if (!craneFound) {
					try {
						lock.wait();
					} catch (InterruptedException ignore) {
						// set interrupt flag and exit loop, this will cause remaining volumes to be skipped and trigger cancel logic in waiting loop
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}
		
		// wait for remaining tasks to finish
		boolean interrupted = false;
		for (int i = 0; i < taskCounter; i++) {
			
			try {
				var future = completionService.take();
			} catch (InterruptedException e) {
				// if not already aborted, cancel remaining tasks, and keep ignoring further interrupts until done waiting
				if (!interrupted) {
					log.info("interrupt received, trying to stop active backup tasks");
					for (var future : futures)
						future.cancel(true);
					interrupted = true;
				}
			}
		}
		
		// reapply interrupted flag, if interrupt was received
		if (interrupted)
			Thread.currentThread().interrupt();
	}
	
	private void backupVolume(SalvageVolume volume, SalvageCrane crane, VolumeLog volumeLog) {
		try {
			volumeLog.start();
			var vessel = new SalvageVessel(docker, volume, crane, hostMeta, volumeLog);
			vessel.start();
			volumeLog.success();
		} catch (Throwable e) {
			log.error("error while backing up volume '{}'", volume.name(), e);
			volumeLog.failure(e);
		}
	}
	
	private record CranePool(Semaphore semaphore) {
		
		public static CranePool fromCrane(SalvageCrane crane) {
			return new CranePool(new Semaphore(crane.maxConcurrent()));
		}
	}
}
