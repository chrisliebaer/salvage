package de.chrisliebaer.salvage.entity;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import java.util.HashMap;
import java.util.Map;

/**
 * A tide defines a common set of volumes that will be backed up at the same time. This allows salvage to minimize container downtime by shutting down containers only
 * once and backup the entire application. It also allows to coordinate backups on a system level, rather than a compose project level.
 *
 * @param name          Name of the tide.
 * @param crane         Crane that will be used to backup the tide.
 * @param groupingMode  Grouping controls how the volumes of this tide are grouped. The volumes of each group will be backed up at the same time.
 * @param cron          Cron expression that defines the time when this tide will be executed.
 * @param maxConcurrent Maximum number of backups that will be executed at the same time, regardless of crane capacities.
 */
public record SalvageTide(String name, SalvageCrane crane, GroupingMode groupingMode, Cron cron, int maxConcurrent) {
	
	private static final CronParser UNIX_CRONTAB_PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
	
	/**
	 * Controlls granularity of backup operations
	 */
	public enum GroupingMode {
		/**
		 * Backup each volume individually, even if that means stopping the same container twitce.
		 */
		INDIVIDUAL,
		/**
		 * Divide affected containers into groups with no dependencies on same volume and backup them individually. Minimizes total downtime but ignores dependencies
		 * between linked services. Meaning that an application and it's database might be backed up separately, leaking to more downtime.
		 */
		SMART,
		/**
		 * Same as smart, but don't split backup sets of compose projects. Sacrifices uptime of single compose services but reduces downtime of entire compose project.
		 */
		PROJECT;
		
		public static GroupingMode fromString(String value) {
			return switch (value) {
				case "individual" -> INDIVIDUAL;
				case "smart" -> SMART;
				case "project" -> PROJECT;
				default -> throw new IllegalArgumentException("Unknown grouping mode: " + value);
			};
		}
	}
	
	private static final String LABEL_TIDE_CRON_SUFFIX = ".cron";
	private static final String LABEL_TIDE_GROUPING_SUFFIX = ".grouping";
	private static final String LABEL_TIDE_CRANE_SUFFIX = ".crane";
	private static final String LABEL_TIDE_MAX_CONCURRENT_SUFFIX = ".maxConcurrent";
	
	public static SalvageTide fromLabels(String name, String prefix, Map<String, String> labels, HashMap<String, SalvageCrane> cranes) {
		var cronExpression = labels.get(prefix + LABEL_TIDE_CRON_SUFFIX);
		if (cronExpression == null)
			throw new IllegalArgumentException("tried to construct tide '" + name + "', but no cron expression was found");
		var cron = UNIX_CRONTAB_PARSER.parse(cronExpression).validate();
		
		var craneName = labels.get(prefix + LABEL_TIDE_CRANE_SUFFIX);
		if (craneName == null)
			throw new IllegalArgumentException("tried to construct tide '" + name + "', but no crane image was specified");
		
		var crane = cranes.get(craneName);
		if (crane == null)
			throw new IllegalArgumentException("tried to construct tide '" + name + "', but crane '" + craneName + "' is not known");
		
		var grouping = labels.get(prefix + LABEL_TIDE_GROUPING_SUFFIX);
		if (grouping == null)
			throw new IllegalArgumentException("tried to construct tide '" + name + "', but no grouping mode was specified");
		
		int maxConcurrent = Integer.MAX_VALUE;
		try {
			var s = labels.get(prefix + LABEL_TIDE_MAX_CONCURRENT_SUFFIX);
			if (s != null)
				maxConcurrent = Integer.parseInt(s);
		} catch (NumberFormatException ignore) {
			throw new IllegalArgumentException("tried to construct tide '" + name + "', but maxConcurrent is not a number");
		}
		
		return new SalvageTide(name, crane, GroupingMode.fromString(grouping), cron, maxConcurrent);
	}
	
}
