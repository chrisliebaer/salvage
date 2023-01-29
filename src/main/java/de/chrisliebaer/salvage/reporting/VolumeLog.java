package de.chrisliebaer.salvage.reporting;

import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import lombok.Getter;

public class VolumeLog {
	
	@Getter private final SalvageVolume volume;
	@Getter private final SalvageCrane crane;
	private final CaptainHook hook;
	
	@Getter private final StopWatch stopWatch = new StopWatch();
	@Getter private String message = "Volume backup has never recorded any activity.";
	
	@Getter private FinishState state = FinishState.UNKNOWN;
	
	public VolumeLog(SalvageVolume volume, SalvageCrane crane, CaptainHook hook) {
		this.volume = volume;
		this.crane = crane;
		this.hook = hook;
	}
	
	public void log(String message) {
		this.message = message;
	}
	
	public void start() {
		stopWatch.start();
	}
	
	public void success() {
		stopWatch.stop();
		if (state != FinishState.UNKNOWN)
			throw new IllegalStateException("Volume state has already been set to '" + state + "'");
		state = FinishState.SUCCESS;
		
		hook.reportVolumeSuccess(volume, crane, stopWatch.duration());
	}
	
	public void failure(String message) {
		stopWatch.stop();
		if (state != FinishState.UNKNOWN)
			throw new IllegalStateException("Volume state has already been set to '" + state + "'");
		
		state = FinishState.FAILURE;
		this.message = message;
		
		hook.reportVolumeFailure(volume, crane, message, stopWatch.duration());
	}
	
	public void failure(Throwable e) {
		stopWatch.stop();
		failure(e.getMessage());
	}
}
