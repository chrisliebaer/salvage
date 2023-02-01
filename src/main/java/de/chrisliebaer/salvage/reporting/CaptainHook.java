package de.chrisliebaer.salvage.reporting;

import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageTide;
import de.chrisliebaer.salvage.entity.SalvageVolume;

import java.time.Duration;
import java.util.Collection;

/**
 * Implementations of this interface provide reporting facilities to provide feedback to external systems and will be invoked after a tide has finished in order to report
 * the results.
 */
public interface CaptainHook {
	
	/**
	 * Called when a volume has been successfully backed up.
	 *
	 * @param volume   Volume that has been backed up.
	 * @param crane    Crane that was used to back up the volume.
	 * @param duration Duration of the backup.
	 */
	void reportVolumeSuccess(SalvageVolume volume, SalvageCrane crane, Duration duration);
	
	/**
	 * Called when a volume backup has failed.
	 *
	 * @param volume   Volume that has been backed up.
	 * @param crane    Crane that was used to back up the volume.
	 * @param message  Message describing the failure or last message reported by the crane.
	 * @param duration Duration of the backup up until the failure occurred.
	 */
	void reportVolumeFailure(SalvageVolume volume, SalvageCrane crane, String message, Duration duration);
	
	/**
	 * Called when a tide has been successfully completed.
	 *
	 * @param tide     Tide that has been completed.
	 * @param volumes  List of volumes that have been successfully backed up.
	 * @param duration Duration of the tide.
	 */
	void reportTideSuccess(SalvageTide tide, Collection<SalvageVolume> volumes, Duration duration);
	
	/**
	 * Called when a tide has failed with no information about invidual volumes.
	 *
	 * @param tide     Tide that has been completed.
	 * @param message  Message describing the failure.
	 * @param duration Duration of the tide up until the failure occurred.
	 */
	void reportTideFailure(SalvageTide tide, String message, Duration duration);
	
	/**
	 * Called when a tide has failed but was able to report to collect information about individual volumes.
	 *
	 * @param tide     Tide that has been completed.
	 * @param success  List of volumes that have been successfully backed up.
	 * @param failure  List of volumes that have failed to be backed up.
	 * @param duration Duration of the tide up until the failure occurred.
	 */
	void reportTideFailure(SalvageTide tide, Collection<SalvageVolume> success, Collection<SalvageVolume> failure, String message, Duration duration);
}
