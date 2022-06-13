package de.chrisliebaer.salvage;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import lombok.extern.log4j.Log4j2;

@SuppressWarnings("CallToSystemExit")
@Log4j2
public enum SalvageMain {
	;
	
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
}
