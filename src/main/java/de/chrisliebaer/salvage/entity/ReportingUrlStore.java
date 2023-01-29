package de.chrisliebaer.salvage.entity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;


public record ReportingUrlStore(Optional<URI> tideSuccess, Optional<URI> tideFailure, Optional<URI> volumeSuccess, Optional<URI> volumeFailure, Method method) {
	
	public enum Method {
		POST, GET
	}
	
	private static final String LABEL_TIDE_REPORT_TIDE_SUCCESS_SUFFIX = ".tide.success";
	private static final String LABEL_TIDE_REPORT_TIDE_FAILURE_SUFFIX = ".tide.failure";
	private static final String LABEL_TIDE_REPORT_VOLUME_SUCCESS_SUFFIX = ".volume.success";
	private static final String LABEL_TIDE_REPORT_VOLUME_FAILURE_SUFFIX = ".volume.failure";
	private static final String LABEL_TIDE_REPORT_METHOD = ".method";
	
	public static ReportingUrlStore fromEnv(Map<String, String> labels, String prefix) throws URISyntaxException {
		var tideSuccess = parse(labels.get(prefix + LABEL_TIDE_REPORT_TIDE_SUCCESS_SUFFIX));
		var tideFailure = parse(labels.get(prefix + LABEL_TIDE_REPORT_TIDE_FAILURE_SUFFIX));
		var volumeSuccess = parse(labels.get(prefix + LABEL_TIDE_REPORT_VOLUME_SUCCESS_SUFFIX));
		var volumeFailure = parse(labels.get(prefix + LABEL_TIDE_REPORT_VOLUME_FAILURE_SUFFIX));
		
		var method = Optional.ofNullable(labels.get(prefix + LABEL_TIDE_REPORT_METHOD))
				.map(String::toUpperCase)
				.map(Method::valueOf)
				.orElse(Method.POST);
		
		return new ReportingUrlStore(tideSuccess, tideFailure, volumeSuccess, volumeFailure, method);
	}
	
	private static Optional<URI> parse(String url) throws URISyntaxException {
		if (url == null)
			return Optional.empty();
		
		return Optional.of(new URI(url));
	}
}
