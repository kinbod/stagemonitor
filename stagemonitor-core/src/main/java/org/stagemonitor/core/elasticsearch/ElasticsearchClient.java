package org.stagemonitor.core.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.core.pool.JavaThreadPoolMetricsCollectorImpl;
import org.stagemonitor.core.pool.PooledResourceMetricsRegisterer;
import org.stagemonitor.core.util.CompletedFuture;
import org.stagemonitor.core.util.DateUtils;
import org.stagemonitor.core.util.ExecutorUtils;
import org.stagemonitor.core.util.HttpClient;
import org.stagemonitor.core.util.JsonMerger;
import org.stagemonitor.core.util.JsonUtils;
import org.stagemonitor.util.IOUtils;
import org.stagemonitor.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.stagemonitor.core.util.JsonMerger.mergeStrategy;

public class ElasticsearchClient {

	public static final Map<String, String> CONTENT_TYPE_JSON = Collections.singletonMap("Content-Type", "application/json");
	public static final Map<String, String> CONTENT_TYPE_NDJSON = Collections.singletonMap("Content-Type", "application/x-ndjson");
	private static final String BULK = "/_bulk";
	private final Logger logger = LoggerFactory.getLogger(ElasticsearchClient.class);
	private final String TITLE = "title";
	private final HttpClient httpClient;
	private final CorePlugin corePlugin;
	private final AtomicBoolean elasticsearchAvailable = new AtomicBoolean(true);

	private final ThreadPoolExecutor asyncESPool;
	private Timer timer;

	public ElasticsearchClient(final CorePlugin corePlugin, final HttpClient httpClient, int esAvailabilityCheckIntervalSec) {
		this.corePlugin = corePlugin;
		asyncESPool = ExecutorUtils
				.createSingleThreadDeamonPool("async-elasticsearch", corePlugin.getThreadPoolQueueCapacityLimit(), corePlugin);
		timer = new Timer("elasticsearch-tasks", true);
		if (corePlugin.isInternalMonitoringActive()) {
			JavaThreadPoolMetricsCollectorImpl pooledResource = new JavaThreadPoolMetricsCollectorImpl(asyncESPool, "internal.asyncESPool");
			PooledResourceMetricsRegisterer.registerPooledResource(pooledResource, Stagemonitor.getMetric2Registry());
		}
		this.httpClient = httpClient;

		if (esAvailabilityCheckIntervalSec > 0) {
			final long period = TimeUnit.SECONDS.toMillis(esAvailabilityCheckIntervalSec);
			timer.scheduleAtFixedRate(new CheckEsAvailability(httpClient, corePlugin), 0, period);
		}
	}

	public JsonNode getJson(final String path) throws IOException {
		return JsonUtils.getMapper().readTree(new URL(corePlugin.getElasticsearchUrl() + path).openStream());
	}

	public <T> T getObject(final String path, Class<T> type) {
		try {
			return JsonUtils.getObjectReader(type).readValue(getJson(path).get("_source"));
		} catch (FileNotFoundException e) {
			return null;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public <T> Collection<T> getAll(String path, int limit, Class<T> clazz) {
		try {
			JsonNode hits = getJson(path + "/_search?size=" + limit).get("hits").get("hits");
			List<T> all = new ArrayList<T>(hits.size());
			ObjectReader reader = JsonUtils.getObjectReader(clazz);
			for (JsonNode hit : hits) {
				all.add(reader.<T>readValue(hit.get("_source")));
			}
			return all;
		} catch (IOException e) {
			logger.warn(e.getMessage(), e);
			return Collections.emptyList();
		}
	}

	public int sendRequest(final String method, final String path) {
		return sendAsJson(method, path, null);
	}

	public int sendAsJson(final String method, final String path, final Object requestBody) {
		if (!isElasticsearchAvailable()) {
			return -1;
		}
		return httpClient.sendAsJson(method, corePlugin.getElasticsearchUrl() + path, requestBody, CONTENT_TYPE_JSON);
	}

	public void index(final String index, final String type, final Object document) {
		if (!isElasticsearchAvailable()) {
			return;
		}
		final ObjectNode json = JsonUtils.toObjectNode(document);
		removeDisallowedCharsFromPropertyNames(json);

		sendAsJsonAsync("POST", "/" + index + "/" + type, json);
	}

	private void removeDisallowedCharsFromPropertyNames(ObjectNode json) {
		final Iterator<String> fieldNames = json.fieldNames();
		List<String> toRemove = new LinkedList<String>();
		Map<String, JsonNode> newProperties = new HashMap<String, JsonNode>();
		while (fieldNames.hasNext()) {
			String fieldName = fieldNames.next();
			final JsonNode value = json.get(fieldName);
			if (fieldName.indexOf('.') != -1) {
				newProperties.put(fieldName.replace(".", "_(dot)_"), value);
				toRemove.add(fieldName);
			}
			if (value.isObject()) {
				removeDisallowedCharsFromPropertyNames((ObjectNode) value);
			}
		}
		json.remove(toRemove);
		json.setAll(newProperties);
	}

	public void updateKibanaIndexPatternAsync(final String indexPatternLocation, final String elasticsearchKibanaIndexPatternPath) {
		asyncESPool.submit(new Runnable() {
			@Override
			public void run() {
				try {
					JsonNode stagemonitorPattern = JsonUtils.getMapper().readTree(IOUtils.getResourceAsStream(indexPatternLocation));
					JsonNode currentPattern = fetchCurrentKibanaIndexPatternConfiguration(elasticsearchKibanaIndexPatternPath);
					JsonNode mergedDefinition = JsonMerger.merge(currentPattern, stagemonitorPattern,
							mergeStrategy().mergeEncodedObjects("fieldFormatMap").encodedArrayWithKey("fields", "name"));
					sendAsJson("PUT", elasticsearchKibanaIndexPatternPath, mergedDefinition);
				} catch (IOException e) {
					logger.error("Error while updating kibana index pattern, definition = {}, pattern path = {}",
							e, indexPatternLocation, elasticsearchKibanaIndexPatternPath);
				} catch (IllegalArgumentException e) {
					logger.error("Error while preparing data for kibana index pattern update, definition = {}, pattern path = {}",
							e, indexPatternLocation, elasticsearchKibanaIndexPatternPath);
				}
			}

			private JsonNode fetchCurrentKibanaIndexPatternConfiguration(String elasticsearchKibanaIndexPatternPath) throws IOException {
				try {
					return getJson(elasticsearchKibanaIndexPatternPath).get("_source");
				} catch (FileNotFoundException e) {
					// kibana returned 404 -> document does not yet exist -> merge stagemonitor configuration with empty object
					return JsonUtils.getMapper().createObjectNode();
				}
			}
		});
	}

	private Future<?> sendAsJsonAsync(final String method, final String path, final Object requestBody) {
		if (isElasticsearchAvailable()) {
			try {
				return asyncESPool.submit(new Runnable() {
					@Override
					public void run() {
						sendAsJson(method, path, requestBody);
					}
				});
			} catch (RejectedExecutionException e) {
				ExecutorUtils.logRejectionWarning(e);
			}
		}
		return new CompletedFuture<Object>(null);
	}

	public Future<?> sendMappingTemplateAsync(String mappingJson, String templateName) {
		return sendAsJsonAsync("PUT", "/_template/" + templateName, mappingJson);
	}

	public static String modifyIndexTemplate(String templatePath, int moveToColdNodesAfterDays, Integer numberOfReplicas, Integer numberOfShards) {
		final JsonNode json;
		try {
			json = JsonUtils.getMapper().readTree(IOUtils.getResourceAsStream(templatePath));
			ObjectNode indexSettings = (ObjectNode) json.get("settings").get("index");
			if (moveToColdNodesAfterDays > 0) {
				indexSettings.put("routing.allocation.require.box_type", "hot");
			}
			if (numberOfReplicas != null && numberOfReplicas >= 0) {
				indexSettings.put("number_of_replicas", numberOfReplicas);
			}
			if (numberOfShards != null && numberOfShards > 0) {
				indexSettings.put("number_of_shards", numberOfShards);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return json.toString();
	}

	public void sendClassPathRessourceBulkAsync(final String resource, boolean logBulkErrors) {
		sendBulkAsync(new HttpClient.OutputStreamHandler() {
			@Override
			public void withHttpURLConnection(OutputStream os) throws IOException {
				IOUtils.copy(IOUtils.getResourceAsStream(resource), os);
				os.close();
			}
		}, logBulkErrors);
	}

	private void sendBulkAsync(final HttpClient.OutputStreamHandler outputStreamHandler, final boolean logBulkErrors) {
		try {
			asyncESPool.submit(new Runnable() {
				@Override
				public void run() {
					sendBulk(outputStreamHandler, logBulkErrors);
				}
			});
		} catch (RejectedExecutionException e) {
			ExecutorUtils.logRejectionWarning(e);
		}
	}

	void sendBulk(HttpClient.OutputStreamHandler outputStreamHandler, boolean logBulkErrors) {
		if (!isElasticsearchAvailable()) {
			return;
		}
		final HttpClient.ResponseHandler<Void> responseHandler = logBulkErrors ? BulkErrorReportingResponseHandler.INSTANCE : HttpClient.NoopResponseHandler.INSTANCE;
		httpClient.send("POST", corePlugin.getElasticsearchUrl() + BULK, CONTENT_TYPE_NDJSON, outputStreamHandler, responseHandler);
	}

	public void deleteIndices(String indexPattern) {
		execute("DELETE", indexPattern + "?timeout=20m&ignore_unavailable=true", "Deleting indices: {}");
	}

	public void forceMergeIndices(String indexPattern) {
		execute("POST", indexPattern + "/_forcemerge?max_num_segments=1&ignore_unavailable=true", "Force merging indices: {}");
	}

	public void updateIndexSettings(String indexPattern, Map<String, ?> settings) {
		if (!isElasticsearchAvailable()) {
			return;
		}
		final String url = corePlugin.getElasticsearchUrl() + "/" + indexPattern + "/_settings?ignore_unavailable=true";
		logger.info("Updating index settings {}\n{}", HttpClient.removeUserInfo(url), settings);
		httpClient.sendAsJson("PUT", url, settings, CONTENT_TYPE_JSON);
	}

	private void execute(String method, String path, String logMessage) {
		if (!isElasticsearchAvailable()) {
			return;
		}
		final String url = corePlugin.getElasticsearchUrl() + "/" + path;
		String urlWithoutAuthInfo = HttpClient.removeUserInfo(url);
		logger.info(logMessage, urlWithoutAuthInfo);
		try {
			httpClient.send(method, url);
		} finally {
			logger.info(logMessage, "Done " + urlWithoutAuthInfo);
		}
	}

	ObjectNode getDashboardForElasticsearch(String dashboardPath) throws IOException {
		final ObjectMapper mapper = JsonUtils.getMapper();
		final ObjectNode dashboard = (ObjectNode) mapper.readTree(IOUtils.getResourceAsStream(dashboardPath));
		dashboard.put("editable", false);
		ObjectNode dashboardElasticsearchFormat = mapper.createObjectNode();
		dashboardElasticsearchFormat.put("user", "guest");
		dashboardElasticsearchFormat.put("group", "guest");
		dashboardElasticsearchFormat.set(TITLE, dashboard.get(TITLE));
		dashboardElasticsearchFormat.set("tags", dashboard.get("tags"));
		dashboardElasticsearchFormat.put("dashboard", dashboard.toString());
		return dashboardElasticsearchFormat;
	}

	public boolean isPoolQueueEmpty() {
		return asyncESPool.getQueue().isEmpty();
	}

	public void waitForCompletion() throws ExecutionException, InterruptedException {
		// because the pool is single threaded,
		// all previously submitted tasks are completed when this task finishes
		asyncESPool.submit(new Runnable() {
			public void run() {
			}
		}).get();
	}

	public void close() {
		asyncESPool.shutdown();
		timer.cancel();
	}

	/**
	 * Performs an optimize and delete on logstash-style index patterns [prefix]YYYY.MM.DD
	 *
	 * @param indexPrefix the prefix of the logstash-style index pattern
	 */
	public void scheduleIndexManagement(String indexPrefix, int optimizeAndMoveIndicesToColdNodesOlderThanDays, int deleteIndicesOlderThanDays) {
		if (deleteIndicesOlderThanDays > 0) {
			final TimerTask deleteIndicesTask = new DeleteIndicesTask(corePlugin.getIndexSelector(), indexPrefix,
					deleteIndicesOlderThanDays, this);
			timer.schedule(deleteIndicesTask, 0, DateUtils.getDayInMillis());
		}

		if (optimizeAndMoveIndicesToColdNodesOlderThanDays > 0) {
			final TimerTask shardAllocationTask = new ShardAllocationTask(corePlugin.getIndexSelector(), indexPrefix,
					optimizeAndMoveIndicesToColdNodesOlderThanDays, this, "cold");
			timer.schedule(shardAllocationTask, 0, DateUtils.getDayInMillis());
		}

		if (optimizeAndMoveIndicesToColdNodesOlderThanDays > 0) {
			final TimerTask optimizeIndicesTask = new ForceMergeIndicesTask(corePlugin.getIndexSelector(), indexPrefix,
					optimizeAndMoveIndicesToColdNodesOlderThanDays, this);
			timer.schedule(optimizeIndicesTask, DateUtils.getNextDateAtHour(3), DateUtils.getDayInMillis());
		}

	}

	public static String getBulkHeader(String action, String index, String type) {
		return "{\"" + action + "\":" +
				"{\"_index\":\"" + index + "\"," +
				"\"_type\":\"" + type + "\"}" +
				"}\n";
	}

	public boolean isElasticsearchAvailable() {
		return !corePlugin.getElasticsearchUrls().isEmpty() && elasticsearchAvailable.get();
	}

	public HttpClient getHttpClient() {
		return httpClient;
	}

	public String getElasticsearchUrl() {
		return corePlugin.getElasticsearchUrl();
	}

	public static class BulkErrorReportingResponseHandler implements HttpClient.ResponseHandler<Void> {

		public static BulkErrorReportingResponseHandler INSTANCE = new BulkErrorReportingResponseHandler();

		private static final int MAX_BULK_ERROR_LOG_SIZE = 256;
		private static final String ERROR_PREFIX = "Error(s) while sending a _bulk request to elasticsearch: {}";

		private static final Logger logger = LoggerFactory.getLogger(BulkErrorReportingResponseHandler.class);

		private BulkErrorReportingResponseHandler() {
		}

		@Override
		public Void handleResponse(InputStream is, Integer statusCode, IOException e) throws IOException {
			if (is == null) {
				logger.warn(e.getMessage(), e);
				return null;
			}
			final JsonNode bulkResponse = JsonUtils.getMapper().readTree(is);
			final JsonNode errors = bulkResponse.get("errors");
			if (errors != null && errors.booleanValue()) {
				logger.warn(ERROR_PREFIX, reportBulkErrors(bulkResponse.get("items")));
			} else if (bulkResponse.get("error") != null) {
				logger.warn(ERROR_PREFIX, bulkResponse);
			}
			return null;
		}

		private String reportBulkErrors(JsonNode items) {
			final StringBuilder sb = new StringBuilder();
			for (JsonNode item : items) {
				JsonNode action = item.get("index");
				if (action == null) {
					action = item.get("create");
				}
				if (action != null) {
					final JsonNode error = action.get("error");
					if (error != null) {
						sb.append("\n - ");
						final JsonNode reason = error.get("reason");
						if (reason != null) {
							sb.append(reason.asText());
							final String errorType = error.get("type").asText();
							if (errorType.equals("version_conflict_engine_exception")) {
								sb.append(": Probably you updated a dashboard in Kibana. ")
										.append("Please don't override the stagemonitor dashboards. ")
										.append("If you want to customize a dashboard, save it under a different name. ")
										.append("Stagemonitor will not override your changes, but that also means that you won't ")
										.append("be able to use the latest dashboard enhancements :(. ")
										.append("To resolve this issue, save the updated one under a different name, delete it ")
										.append("and restart stagemonitor so that the dashboard can be recreated.");
							} else if ("es_rejected_execution_exception".equals(errorType)) {
								sb.append(": Consider increasing threadpool.bulk.queue_size. See also stagemonitor's " +
										"documentation for the Elasticsearch data base.");
							}
						} else {
							sb.append(error.toString());
						}
					}
				} else {
					sb.append(' ');
					final String error = item.toString();
					if (error.length() > MAX_BULK_ERROR_LOG_SIZE) {
						sb.append(error.substring(0, MAX_BULK_ERROR_LOG_SIZE)).append("...");
					} else {
						sb.append(error);
					}
				}
			}
			return sb.toString();
		}

	}

	public abstract static class BulkErrorCountingResponseHandler implements HttpClient.ResponseHandler<Void> {

		@Override
		public Void handleResponse(InputStream is, Integer statusCode, IOException e) throws IOException {
			if (is == null) {
				return null;
			}
			final JsonNode bulkResponse = JsonUtils.getMapper().readTree(is);
			final JsonNode errors = bulkResponse.get("errors");
			if (errors != null && errors.booleanValue()) {
				reportBulkErrors(bulkResponse.get("items"));
			}
			return null;
		}

		private void reportBulkErrors(JsonNode items) {
			int errorCount = 0;
			for (JsonNode item : items) {
				for (JsonNode action : item) {
					if (action.has("error")) {
						errorCount++;
					}
				}
			}
			if (errorCount > 0) {
				onBulkError(errorCount);
			}
		}

		public abstract void onBulkError(int errorCount);
	}

	private class CheckEsAvailability extends TimerTask {
		private final HttpClient httpClient;
		private final CorePlugin corePlugin;

		public CheckEsAvailability(HttpClient httpClient, CorePlugin corePlugin) {
			this.httpClient = httpClient;
			this.corePlugin = corePlugin;
		}

		@Override
		public void run() {
			// TODO actually, the availability check has to be performed for each URL as multiple ES urls can be configured
			// in the future, detect all available nodes in the cluster: http://{oneOfTheProvidedUrls}/_nodes/box_type:hot/none
			// -> response.nodes*.http_address
			final String elasticsearchUrl = corePlugin.getElasticsearchUrl();
			if (StringUtils.isEmpty(elasticsearchUrl)) {
				return;
			}
			httpClient.send("HEAD", elasticsearchUrl + "/", null, null, new HttpClient.ResponseHandler<Object>() {
				@Override
				public Object handleResponse(InputStream is, Integer statusCode, IOException e) throws IOException {
					if (e != null) {
						if (isElasticsearchAvailable()) {
							logger.warn("Elasticsearch is not available. " +
									"Stagemonitor won't try to send documents to Elasticsearch until it is available again.");
						}
							elasticsearchAvailable.set(false);
						} else {
							if (!isElasticsearchAvailable()) {
								logger.info("Elasticsearch is available again.");
							}
							elasticsearchAvailable.set(true);
						}
					return null;
				}
			});
		}
	}
}
