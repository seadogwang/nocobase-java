package com.nocobase.frontend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Converts HAR (HTTP Archive) JSON files to the NocoBase frontend trace replay format.
 * <p>
 * Usage (CLI):
 * <pre>
 * java com.nocobase.frontend.HarToContractConverter input.har output.json --prefix /api/ --trace "my-trace"
 * </pre>
 * <p>
 * Options:
 * <ul>
 *   <li>{@code --prefix /api/} - Only include requests whose URL path starts with this prefix</li>
 *   <li>{@code --trace "trace-name"} - Set the trace name (default: "har-replay")</li>
 *   <li>{@code --description "desc"} - Set the trace description</li>
 *   <li>{@code --pretty} - Pretty-print the output JSON</li>
 * </ul>
 * <p>
 * The output format matches the trace.json schema consumed by
 * {@code ApiCompatibilityTest.replayFrontendTrace()}.
 */
public class HarToContractConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String urlPrefix;
    private final String traceName;
    private final String description;

    public HarToContractConverter(String urlPrefix, String traceName, String description) {
        this.urlPrefix = urlPrefix != null ? urlPrefix : "";
        this.traceName = traceName != null ? traceName : "har-replay";
        this.description = description != null ? description : "Generated from HAR file";
    }

    /**
     * Parse a HAR file and convert its entries to trace steps.
     *
     * @param harFilePath path to the HAR JSON file
     * @return a trace map with "trace", "description", "variables", and "steps"
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> convert(String harFilePath) throws IOException {
        String harJson = new String(Files.readAllBytes(Paths.get(harFilePath)));
        Map<String, Object> har = OBJECT_MAPPER.readValue(harJson, new TypeReference<Map<String, Object>>() {});

        Map<String, Object> log = (Map<String, Object>) har.get("log");
        if (log == null) {
            throw new IllegalArgumentException("HAR file missing 'log' root element");
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) log.get("entries");
        if (entries == null) {
            throw new IllegalArgumentException("HAR file missing 'log.entries' array");
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        int stepIndex = 0;

        for (Map<String, Object> entry : entries) {
            Map<String, Object> request = (Map<String, Object>) entry.get("request");
            Map<String, Object> response = (Map<String, Object>) entry.get("response");

            if (request == null || response == null) {
                continue;
            }

            String method = ((String) request.get("method")).toUpperCase();
            String fullUrl = (String) request.get("url");
            String path = extractPath(fullUrl);

            // Filter by URL prefix
            if (!urlPrefix.isEmpty() && !path.startsWith(urlPrefix)) {
                continue;
            }

            stepIndex++;
            Map<String, Object> step = new LinkedHashMap<>();

            // Generate a descriptive name
            String stepName = generateStepName(method, path, stepIndex);
            step.put("name", stepName);
            step.put("method", method);
            step.put("path", path);

            // Determine if auth is required
            boolean requiresAuth = hasAuthHeader(request);
            step.put("requiresAuth", requiresAuth);

            // Extract request body
            Map<String, Object> postData = (Map<String, Object>) request.get("postData");
            if (postData != null && !"GET".equals(method)) {
                String text = (String) postData.get("text");
                if (text != null && !text.isEmpty()) {
                    try {
                        Object body = OBJECT_MAPPER.readValue(text, Object.class);
                        step.put("body", body);
                    } catch (Exception e) {
                        // Not valid JSON, skip body
                    }
                }
            }

            // Extract query params
            Map<String, Object> queryParams = extractQueryParams(fullUrl);
            if (!queryParams.isEmpty()) {
                step.put("query", queryParams);
            }

            // Response status
            int status = ((Number) response.get("status")).intValue();
            step.put("expectedStatus", status);

            // Auto-detect negative assertions for error responses
            if (status >= 400) {
                step.put("negativeAssertion", true);
            }

            // Generate expectedShape from response body
            Map<String, Object> content = (Map<String, Object>) response.get("content");
            if (content != null) {
                String responseText = (String) content.get("text");
                if (responseText != null && !responseText.isEmpty()) {
                    try {
                        Map<String, Object> responseBody = OBJECT_MAPPER.readValue(
                                responseText, new TypeReference<Map<String, Object>>() {});
                        Map<String, Object> shape = inferShape(responseBody, "", 3);
                        if (!shape.isEmpty()) {
                            step.put("expectedShape", shape);
                        }
                    } catch (Exception e) {
                        // Response is not valid JSON, skip shape
                    }
                }
            }

            steps.add(step);
        }

        // Build the trace output
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("trace", traceName);
        trace.put("description", description);
        trace.put("variables", new LinkedHashMap<>());
        trace.put("steps", steps);

        return trace;
    }

    /**
     * Extract the path component from a full URL.
     */
    private String extractPath(String fullUrl) {
        try {
            URI uri = URI.create(fullUrl);
            String path = uri.getPath();
            String query = uri.getQuery();
            if (query != null) {
                return path + "?" + query;
            }
            return path;
        } catch (Exception e) {
            return fullUrl;
        }
    }

    /**
     * Extract query parameters from a URL into a map.
     * Returns only the path (without query) when the trace format uses query params separately.
     */
    private Map<String, Object> extractQueryParams(String fullUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        try {
            URI uri = URI.create(fullUrl);
            String query = uri.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        params.put(kv[0], kv[1]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return params;
    }

    /**
     * Check if the request has an Authorization header.
     */
    @SuppressWarnings("unchecked")
    private boolean hasAuthHeader(Map<String, Object> request) {
        List<Map<String, Object>> headers = (List<Map<String, Object>>) request.get("headers");
        if (headers != null) {
            for (Map<String, Object> header : headers) {
                String name = (String) header.get("name");
                if ("Authorization".equalsIgnoreCase(name) || "authorization".equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Generate a human-readable step name from method, path, and index.
     */
    private String generateStepName(String method, String path, int index) {
        // Extract the last meaningful segment of the path
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String[] segments = cleanPath.split("/");
        String lastSegment = segments[segments.length - 1];
        // Remove colon-style action prefix
        if (lastSegment.contains(":")) {
            lastSegment = lastSegment.substring(lastSegment.indexOf(':') + 1);
        }
        return method + " " + lastSegment + " (#" + index + ")";
    }

    /**
     * Infer the shape (field types) from a JSON response map.
     * Recurses into nested objects and arrays up to maxDepth.
     * Returns a flat map of dot-notation paths to type strings.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> inferShape(Object value, String prefix, int maxDepth) {
        Map<String, Object> shape = new LinkedHashMap<>();

        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (prefix.isEmpty()) {
                // Top-level: infer each key
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    String key = entry.getKey();
                    Object val = entry.getValue();
                    String fullKey = prefix.isEmpty() ? key : prefix + "." + key;

                    if (val instanceof Map && maxDepth > 0) {
                        shape.put(fullKey, "map");
                        shape.putAll(inferShape(val, fullKey, maxDepth - 1));
                    } else if (val instanceof List) {
                        List<?> list = (List<?>) val;
                        shape.put(fullKey, "array");
                        if (!list.isEmpty() && maxDepth > 0) {
                            Object first = list.get(0);
                            if (first instanceof Map) {
                                shape.putAll(inferShape(first, fullKey + "[0]", maxDepth - 1));
                            } else {
                                shape.put(fullKey + "[0]", inferType(first));
                            }
                        }
                    } else {
                        shape.put(fullKey, inferType(val));
                    }
                }
            } else {
                // Nested: add as map
                shape.put(prefix, "map");
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            shape.put(prefix, "array");
            if (!list.isEmpty() && maxDepth > 0) {
                Object first = list.get(0);
                if (first instanceof Map) {
                    shape.putAll(inferShape(first, prefix + "[0]", maxDepth - 1));
                } else {
                    shape.put(prefix + "[0]", inferType(first));
                }
            }
        } else {
            shape.put(prefix, inferType(value));
        }

        return shape;
    }

    /**
     * Map a Java value to a JSON type string.
     */
    private String inferType(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "map";
        return "string";
    }

    /**
     * Write the trace to a JSON file.
     */
    public void writeToFile(Map<String, Object> trace, String outputPath) throws IOException {
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath), trace);
    }

    // ========================================================================
    // CLI entry point
    // ========================================================================

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args[1];
        String urlPrefix = "";
        String traceName = "har-replay";
        String description = "Generated from HAR file";
        boolean pretty = false;

        // Parse optional arguments
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--prefix":
                    if (i + 1 < args.length) urlPrefix = args[++i];
                    break;
                case "--trace":
                    if (i + 1 < args.length) traceName = args[++i];
                    break;
                case "--description":
                    if (i + 1 < args.length) description = args[++i];
                    break;
                case "--pretty":
                    pretty = true;
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        try {
            HarToContractConverter converter = new HarToContractConverter(urlPrefix, traceName, description);
            Map<String, Object> trace = converter.convert(inputFile);

            if (pretty) {
                converter.writeToFile(trace, outputFile);
            } else {
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(trace);
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(json);
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
            int stepCount = steps != null ? steps.size() : 0;
            System.out.println("Converted " + stepCount + " steps from " + inputFile + " to " + outputFile);
            System.out.println("Trace name: " + traceName);
            if (!urlPrefix.isEmpty()) {
                System.out.println("URL prefix filter: " + urlPrefix);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java com.nocobase.frontend.HarToContractConverter <input.har> <output.json> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --prefix <path>      Filter requests by URL path prefix (e.g., /api/)");
        System.out.println("  --trace <name>       Set trace name (default: har-replay)");
        System.out.println("  --description <desc> Set trace description");
        System.out.println("  --pretty             Pretty-print output JSON");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java com.nocobase.frontend.HarToContractConverter recording.har trace.json --prefix /api/ --trace \"frontend-recording\"");
    }
}