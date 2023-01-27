package de.chrisliebaer.salvage.entity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;


public record ReportingUrlStore(Optional<URI> tideSuccess, Optional<URI> tideFailure, Optional<URI> volumeSuccess, Optional<URI> volumeFailure) {
	
	private static final String LABEL_TIDE_REPOR_TIDE_SUCCESS_SUFFIX = ".tide.success";
	private static final String LABEL_TIDE_REPOR_TIDE_FAILURE_SUFFIX = ".tide.failure";
	private static final String LABEL_TIDE_REPOR_VOLUME_SUCCESS_SUFFIX = ".volume.success";
	private static final String LABEL_TIDE_REPOR_VOLUME_FAILURE_SUFFIX = ".volume.failure";
	
	public static ReportingUrlStore fromEnv(Map<String, String> labels, String prefix) throws URISyntaxException {
		var tideSuccess = parse(labels.get(prefix + LABEL_TIDE_REPOR_TIDE_SUCCESS_SUFFIX));
		var tideFailure = parse(labels.get(prefix + LABEL_TIDE_REPOR_TIDE_FAILURE_SUFFIX));
		var volumeSuccess = parse(labels.get(prefix + LABEL_TIDE_REPOR_VOLUME_SUCCESS_SUFFIX));
		var volumeFailure = parse(labels.get(prefix + LABEL_TIDE_REPOR_VOLUME_FAILURE_SUFFIX));
		
		return new ReportingUrlStore(tideSuccess, tideFailure, volumeSuccess, volumeFailure);
	}
	
	private static Optional<URI> parse(String url) throws URISyntaxException {
		if (url == null)
			return Optional.empty();
		
		return Optional.of(new URI(url));
	}
}
