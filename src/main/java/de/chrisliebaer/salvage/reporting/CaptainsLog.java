package de.chrisliebaer.salvage.reporting;

import java.time.Duration;
import java.util.Collection;

public interface CaptainsLog {
	
	void reportVolumeSuccess(String name, String crane, Duration duration);
	
	void reportVolumeFailure(String name, String crane, Duration duration, Throwable e);
	
	void reportTideSuccess(String tide, Collection<String> volumes, Duration duration);
	
	void reportTideFailure(String string, Duration duration, Throwable e);
}
