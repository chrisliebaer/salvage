package de.chrisliebaer.salvage.reporting;

import de.chrisliebaer.salvage.SalvageMain;
import de.chrisliebaer.salvage.entity.ReportingUrlStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.codehaus.plexus.util.IOUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@SuppressWarnings("ReturnOfNull")
@Slf4j
public class WebhookReporter implements CaptainsLog {
	
	private static final int MAX_EXCEPTION_LENGTH = 3000;
	
	private static final String TEMPLATE_VOLUME_SUCCESS;
	private static final String TEMPLATE_VOLUME_FAILURE;
	private static final String TEMPLATE_TIDE_SUCCESS;
	private static final String TEMPLATE_TIDE_FAILURE;
	
	static {
		try {
			var cl = WebhookReporter.class.getClassLoader();
			TEMPLATE_VOLUME_SUCCESS = IOUtil.toString(cl.getResourceAsStream("report-templates/discordVolumeSuccess.json"));
			TEMPLATE_VOLUME_FAILURE = IOUtil.toString(cl.getResourceAsStream("report-templates/discordVolumeFailure.json"));
			TEMPLATE_TIDE_SUCCESS = IOUtil.toString(cl.getResourceAsStream("report-templates/discordTideSuccess.json"));
			TEMPLATE_TIDE_FAILURE = IOUtil.toString(cl.getResourceAsStream("report-templates/discordTideFailure.json"));
		} catch (IOException e) {
			throw new RuntimeException("Failed to load report templates", e);
		}
	}
	
	
	private final ReportingUrlStore store;
	private final String host;
	private final HttpClient client;
	
	
	public WebhookReporter(ReportingUrlStore store, String host, HttpClient client) {
		this.store = store;
		this.host = host;
		this.client = client;
	}
	
	private Map<String, String> defaultMap() {
		var map = new HashMap<String, String>();
		map.put("host", host);
		return map;
	}
	
	@Override
	public void reportVolumeSuccess(String name, String crane, Duration duration) {
		store.volumeSuccess().ifPresent(uri -> {
			var map = defaultMap();
			map.put("volume", name);
			map.put("crane", crane);
			map.put("duration", SalvageMain.formatDuration(duration));
			
			send(map, uri, TEMPLATE_VOLUME_SUCCESS)
					.exceptionally(e -> {
						log.error("Failed to send webhook for backup success of volume '{}'", name, e);
						return null;
					});
		});
	}
	
	@Override
	public void reportVolumeFailure(String name, String crane, Duration duration, Throwable e) {
		store.volumeFailure().ifPresent(uri -> {
			var map = defaultMap();
			map.put("volume", name);
			map.put("crane", crane);
			map.put("duration", SalvageMain.formatDuration(duration));
			map.put("exception", StringUtils.abbreviate(e.getMessage(), MAX_EXCEPTION_LENGTH));
			
			send(map, uri, TEMPLATE_VOLUME_FAILURE)
					.exceptionally(e1 -> {
						log.error("Failed to send webhook for backup failure of volume '{}'", name, e1);
						return null;
					});
		});
	}
	
	@Override
	public void reportTideSuccess(String tide, Collection<String> volumes, Duration duration) {
		store.tideSuccess().ifPresent(uri -> {
			var map = defaultMap();
			map.put("tide", tide);
			map.put("volumes", volumes.stream().map(s -> "`" + s + "`").collect(Collectors.joining(", ")));
			map.put("duration", SalvageMain.formatDuration(duration));
			
			send(map, uri, TEMPLATE_TIDE_SUCCESS)
					.exceptionally(e -> {
						log.error("Failed to send webhook for tide success '{}'", tide, e);
						return null;
					});
		});
	}
	
	@Override
	public void reportTideFailure(String string, Duration duration, Throwable e) {
		store.tideFailure().ifPresent(uri -> {
			var map = defaultMap();
			map.put("tide", string);
			map.put("duration", SalvageMain.formatDuration(duration));
			map.put("exception", StringUtils.abbreviate(e.getMessage(), MAX_EXCEPTION_LENGTH));
			
			send(map, uri, TEMPLATE_TIDE_FAILURE)
					.exceptionally(e1 -> {
						log.error("Failed to send webhook for tide failure '{}'", string, e1);
						return null;
					});
		});
	}
	
	private CompletableFuture<HttpResponse<Void>> send(Map<String, String> map, URI url, String template) {
		map.replaceAll((k, v) -> StringEscapeUtils.escapeJson(v));
		
		StrSubstitutor sub = new StrSubstitutor(map);
		var body = sub.replace(template);
		
		var req = HttpRequest.newBuilder(url)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		
		return client.sendAsync(req, HttpResponse.BodyHandlers.discarding());
	}
}
