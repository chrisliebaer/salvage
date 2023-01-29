package de.chrisliebaer.salvage.reporting;

import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageTide;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This class is used to store the results of a tide. It allows creating sub reports for each volume. The tide report has two kinds of failures it can record:
 * <ul>
 *     <li>Failure to establish the tide itself due to daemon outages</li>
 *     <li>Failure to back up an individual volume.</li>
 * </ul>
 * <p>
 * The tide will only be considered successful if all volumes were backed up successfully and the tide itself did not report any failures.
 */
public class TideLog {
	
	public record TideResult(FinishState state, String message) {}
	
	private final Map<SalvageVolume, VolumeLog> volumeLogs = new HashMap<>();
	
	@Getter private final StopWatch stopWatch = new StopWatch();
	@Getter private final SalvageTide tide;
	private final CaptainHook hook;
	
	private FinishState tideState = FinishState.UNKNOWN;
	private String message = "No message has been reported by tide";
	
	public TideLog(SalvageTide tide, CaptainHook hook) {
		this.tide = tide;
		this.hook = hook;
	}
	
	/**
	 * Create a new volume log for the given volume. If a volume log already exists for the given volume, it will be returned.
	 *
	 * @param volume Volume to create log for.
	 * @param crane  Crane that will be used to back up the volume.
	 * @return Volume log for the given volume.
	 */
	public VolumeLog getVolumeLog(SalvageVolume volume, SalvageCrane crane) {
		var volumeLog = volumeLogs.computeIfAbsent(volume, v -> new VolumeLog(v, crane, hook));
		if (!volumeLog.crane().equals(crane))
			throw new IllegalArgumentException("Volume log for volume '" + volume + "' already exists with different crane '" + volumeLog.crane() + "'");
		return volumeLog;
	}
	
	public void start() {
		stopWatch.start();
	}
	
	public void success() {
		stopWatch.stop();
		if (tideState != FinishState.UNKNOWN)
			throw new IllegalStateException("Volume state has already been set to '" + tideState + "'");
		tideState = FinishState.SUCCESS;
	}
	
	public void failure(String message) {
		stopWatch.stop();
		if (tideState != FinishState.UNKNOWN)
			throw new IllegalStateException("Volume state has already been set to '" + tideState + "'");
		
		tideState = FinishState.FAILURE;
		this.message = message;
	}
	
	public void failure(Throwable e) {
		stopWatch.stop();
		var s = e.getMessage();
		if (s == null || s.isBlank())
			s = e.getClass().getSimpleName();
		failure(s);
	}
	
	public TideResult tideResult() {
		// if all volumes are successful, the tide state is reported, otherwise the volume dictates the tide state
		return findMostSevereVolumeLog()
				.map(volumeLog -> new TideResult(FinishState.FAILURE, "volume backup '" + volumeLog.volume().name() + "' reported: " + volumeLog.message()))
				.orElse(new TideResult(tideState, message));
	}
	
	public Collection<VolumeLog> volumeLogs() {
		return Collections.unmodifiableCollection(volumeLogs.values());
	}
	
	/**
	 * Checks all known {@link VolumeLog} and returns the one with the most severe state. That is any volume that has failed, if any volume has failed. Otherwise, it's a
	 * volume that has an unknown state, if any volume has an unknown state. If no volume has failed or unkown state, we report no volume as most severe.
	 *
	 * @return Most severe volume log or none, if no volume has failed or unknown state.
	 */
	private Optional<VolumeLog> findMostSevereVolumeLog() {
		Optional<VolumeLog> mostSevereLog = Optional.empty();
		for (var volumeLog : volumeLogs.values()) {
			var volumeState = volumeLog.state();
			
			// failure is always more severe than unknown, if multiple volumes have failed we will report the first one
			if (volumeState == FinishState.FAILURE)
				return Optional.of(volumeLog);
			
			if (volumeState == FinishState.UNKNOWN)
				mostSevereLog = Optional.of(volumeLog);
		}
		
		return mostSevereLog;
	}
}
