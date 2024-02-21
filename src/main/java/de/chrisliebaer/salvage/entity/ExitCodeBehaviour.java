package de.chrisliebaer.salvage.entity;

import com.google.common.collect.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


/**
 * Represents the behaviour of a command in response to its exit code.
 * <ul>
 *     <li>The Ignore record represents a behaviour that ignores the exit code of a command and continues with the backup regardless.</li>
 *     <li>The FailIfNonZero record represents a behaviour that stops the backup if the command exits with a non-zero exit code.</li>
 *     <li>The Custom record represents a behaviour that reacts to the exit code in a custom way, defined by a list of ranges.</li>
 * </ul>
 * <p>
 * The interface defines a single method, check, which takes a long value representing an exit code and returns a boolean.
 */
public sealed interface ExitCodeBehaviour permits ExitCodeBehaviour.Ignore, ExitCodeBehaviour.FailIfNonZero, ExitCodeBehaviour.Custom {
	
	static ExitCodeBehaviour fromString(String value) {
		// first check for built-in behaviours, if none matches, parse custom behaviour, if that fails, throw exception1
		
		if ("ignore".equals(value)) {
			return new Ignore();
		}
		if ("fail".equals(value)) {
			return new FailIfNonZero();
		}
		return Custom.fromString(value);
	}
	
	boolean check(long exitCode);
	
	/**
	 * Describes behaviour which ignores the exit code of the command and continues with the backup regardless.
	 */
	record Ignore() implements ExitCodeBehaviour {
		
		@Override
		public boolean check(long exitCode) {
			return true;
		}
	}
	
	/**
	 * Describes behaviour which stops the backup if the command exits with a non-zero exit code.
	 */
	record FailIfNonZero() implements ExitCodeBehaviour {
		
		@Override
		public boolean check(long exitCode) {
			return exitCode == 0;
		}
	}
	
	/**
	 * Describes behaviour which reacts to the exit code in a custom way.
	 */
	record Custom(List<Range<Long>> ranges) implements ExitCodeBehaviour {
		
		/**
		 * This pattern matches a single number or a range of numbers separated by a hyphen. Each number can be prefixed with a minus sign to indicate a negative number.
		 * <p>Examples of valid ranges:</p>
		 * <ul>
		 *   <li>1-5</li>
		 *   <li>-5--1</li>
		 *   <li>1</li>
		 *   <li>-1</li>
		 *   <li>1--1</li>
		 *   <li>-2-1</li>
		 *   </ul>
		 */
		private static final Pattern RANGE_PATTERN = Pattern.compile("(?<start>-?\\d+)-(?<end>-?\\d+)|(?<single>-?\\d+)");
		
		/**
		 * Parses a string representation of the custom exit code behaviour.
		 *
		 * <p>
		 * The string is expected to be a comma-separated list of ranges, where each range is either a single number or a range of numbers separated by a hyphen.
		 * </p>
		 *
		 * @param str the string representation.
		 */
		public static Custom fromString(String str) {
			// remove whitespace, allow pesky humans to add spaces
			var value = str.replaceAll("\\s", "");
			var parts = value.split(",");
			
			var ranges = new ArrayList<Range<Long>>();
			for (var part : parts) {
				var matcher = RANGE_PATTERN.matcher(part);
				
				if (!matcher.matches()) {
					throw new IllegalArgumentException("invalid range: " + part);
				}
				
				var start = matcher.group("start");
				var end = matcher.group("end");
				if (start == null) {
					var number = parseNumber(matcher.group("single"));
					ranges.add(Range.singleton(number));
				} else {
					var startNumber = parseNumber(start);
					var endNumber = parseNumber(end);
					
					// swap if start is greater than end
					if (startNumber > endNumber) {
						var temp = startNumber;
						startNumber = endNumber;
						endNumber = temp;
					}
					
					ranges.add(Range.closed(startNumber, endNumber));
				}
			}
			return new Custom(ranges);
		}
		
		private static long parseNumber(String str) {
			var isNegative = str.startsWith("-");
			var value = str;
			if (isNegative) {
				value = str.substring(1);
			}
			var number = Long.parseLong(value);
			return isNegative ? -number : number;
		}
		
		@Override
		public boolean check(long exitCode) {
			return ranges.stream().anyMatch(r -> r.contains(exitCode));
		}
	}
}
