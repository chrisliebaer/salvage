package de.chrisliebaer.salvage.reporting;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

public class StopWatch {
	private Instant start;
	@Getter private Duration duration = Duration.ZERO;
	
	public void start() {
		if (start != null)
			throw new IllegalStateException("StopWatch has already been started");
		start = Instant.now();
	}
	
	public void stop() {
		if (start == null)
			throw new IllegalStateException("StopWatch has not been started");
		duration = Duration.between(start, Instant.now());
	}
}
