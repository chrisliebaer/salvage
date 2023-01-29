package de.chrisliebaer.salvage;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.core.config.Configurator;

import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

@SuppressWarnings("CallToSystemExit")
@Log4j2
public enum SalvageMain {
	;
	
	public static void main(String[] args) throws URISyntaxException {
		var verbose = System.getenv("VERBOSE");
		if (verbose != null) {
			switch (verbose.toLowerCase(Locale.ROOT)) {
				case "true", "1", "yes" -> Configurator.reconfigure(SalvageMain.class.getClassLoader().getResource("log4j2-verbose.xml").toURI());
			}
			log.info("verbose logging enabled");
		}
		
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
	
	public static String formatDuration(Duration duration) {
		// https://stackoverflow.com/a/40487511/1834100
		return duration.toString()
				.substring(2)
				.replaceAll("(\\d[HMS])(?!$)", "$1 ")
				.replaceAll("\\.\\d+", "")
				.toLowerCase();
	}
}
