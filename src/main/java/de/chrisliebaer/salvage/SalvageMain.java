package de.chrisliebaer.salvage;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/*
Kommunikation mit crane über socket im container

0. Nach Start eigene Umgebung erfassen
1. Auf nächste Tide warten
2. Volumes identifizieren, die von Tide gesichert werden müssen
außerdem: individual, smart, project (steuert gruppierung)
3b. Nachfolgende Aktionen für jede dieser Komponenten ausführen

4. Checkpoint: Volumes befinden sich in Backup State
5. crane config laden (wird über envvars in salvage konfiguriert)
	* image name
	* env vars
	* volume mounts
6. crane parellel auf alle volumes ausführen (parellel, wenn von crane erlaubt)
6.a crane meldet status irgendwie zurück

7. Container state wiederherstellen
8. Tide complete

Beispiel für mich:
hetzner-borg-crane:
- image chrisliebaer/crane-borg
- keine Volumes
- ENV: secret login

daily-backup-tide:
- 4 uhr nachts

volumes:
irgendwas
- tide: daily-backups
- crane: hetzner-borg-crane
 */

@SuppressWarnings("CallToSystemExit")
@Log4j2
public enum SalvageMain {
	;
	
	private static final Pattern CONTAINER_ID_IN_CPUSET_PATTERN = Pattern.compile("^/system\\.slice/docker-(?<id>[a-f\\d]+)\\.scope");
	private static final File CPUSET_FILE = new File("/proc/1/cpuset");
	
	public static void main(String[] args) {
		
		var service = new SalvageService();
		service.addListener(new Service.Listener() {
			@Override
			public void starting() {
				log.info("salvage service starting up");
			}
			
			@Override
			public void running() {
				log.info("salvage service up and running");
			}
			
			@Override
			public void stopping(Service.State from) {
				log.info("salvage service stopping (was: {})", from);
			}
			
			@Override
			public void terminated(Service.State from) {
				log.info("salvage service terminated (was: {})", from);
				System.exit(0);
			}
			
			@Override
			public void failed(Service.State from, Throwable failure) {
				log.error("salvage service encountered error", failure);
				System.exit(-1);
			}
		}, MoreExecutors.directExecutor());
		service.startAsync();
		
		
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("termination signal received, stopping salvage service, please wait...");
			service.stopAsync().awaitTerminated();
		}, "SalvageShutdownHook"));
	}
	
	/**
	 * Undocumented and hacky method to get own container id. May break at any point.
	 *
	 * @return The container id of the container this application is currently running in.
	 * @throws IllegalStateException If fetching the container id failed.
	 */
	public static String getOwnContainerId() {
		try {
			var cpuset = FileUtils.readFileToString(new File("/proc/1/cpuset"), StandardCharsets.UTF_8);
			var matcher = CONTAINER_ID_IN_CPUSET_PATTERN.matcher(cpuset);
			if (matcher.find()) {
				return matcher.group("id");
			}
			
			throw new IllegalStateException("failed to fetch own container id from " + CPUSET_FILE + ". Content was: " + cpuset);
		} catch (IOException e) {
			throw new IllegalStateException("failed to read " + CPUSET_FILE, e);
		}
	}
}
