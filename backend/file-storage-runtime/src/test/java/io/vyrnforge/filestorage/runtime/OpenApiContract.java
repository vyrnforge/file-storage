package io.vyrnforge.filestorage.runtime;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only checker for the schema subset used by our three v1 contracts.
 * No claim of a general JSON Schema or OpenAPI validator.
 */
final class OpenApiContract {
    private OpenApiContract() {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> DOCUMENTS = List.of("file-storage-v1.yaml", "policy-admin-v1.yaml", "backend-admin-v1.yaml");

    private record Operation(Map<?, ?> document, Map<?, ?> operation) {}

    private static Map<?, ?> document(String filename) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("contracts/openapi"))) root = root.getParent();
        assertNotNull(root, "contract directory");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var reader = Files.newBufferedReader(root.resolve("contracts/openapi").resolve(filename))) {
            return map(new Yaml(new SafeConstructor(options)).load(reader));
        }
    }

    static Set<String> operations() throws Exception {
        Set<String> result = new HashSet<>();
        for (String name : DOCUMENTS) {
            var doc = document(name);
            assertEquals("3.1.0", doc.get("openapi"));
            for (var path : map(doc.get("paths")).entrySet()) {
                for (var method : map(path.getValue()).entrySet()) {
                    if (Set.of("get", "post", "put", "delete", "patch").contains(method.getKey())) {
                        assertTrue(result.add(method.getKey()+" "+path.getKey()), "duplicate operation");
                    }
                }
            }
        }
        return result;
    }

    private static Operation operation(HttpResponse<String> response) throws Exception {
        String path = response.request().uri().getPath();
        String method = response.request().method().toLowerCase(Locale.ROOT);
        List<Operation> matches = new ArrayList<>();
        for (String name : DOCUMENTS) {
            var doc = document(name);
            for (var entry : map(doc.get("paths")).entrySet()) {
                String template = (String) entry.getKey();
                String regex = Arrays.stream(template.split("/", -1))
                        .map(segment -> segment.startsWith("{") ? "[^/]+" : Pattern.quote(segment))
                        .collect(java.util.stream.Collectors.joining("/"));
                var methods = map(entry.getValue());
                if (path.matches(regex) && methods.containsKey(method))
                    matches.add(new Operation(doc, map(methods.get(method))));
            }
        }
        assertEquals(1, matches.size(), method+" "+path);
        return matches.getFirst();
    }

    static void assertResponse(HttpResponse<String> response) throws Exception {
        var operation = operation(response);
        var responses = map(operation.operation().get("responses"));
        Object definition = responses.get(Integer.toString(response.statusCode()));
        if (definition == null) definition = responses.get("default");
        assertNotNull(definition, "undocumented status "+response.statusCode());
        var resolved = resolve(operation.document(), map(definition));
        var content = map(map(resolved.get("content")).get("application/json"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        for (String header : List.of("X-Correlation-Id", "X-Request-Id"))
            UUID.fromString(response.headers().firstValue(header).orElseThrow());
        if (response.statusCode() == 401)
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        Object body = JSON.readValue(response.body(), Object.class);
        validate(operation.document(), map(content.get("schema")), body, "$");
    }

    static void assertRequest(HttpResponse<String> response, String body) throws Exception {
        var operation = operation(response);
        var request = resolve(operation.document(), map(operation.operation().get("requestBody")));
        var content = map(map(request.get("content")).get("application/json"));
        validate(operation.document(), map(content.get("schema")), JSON.readValue(body, Object.class), "$");
    }

    private static Map<?, ?> resolve(Map<?, ?> root, Map<?, ?> schema) {
        if (!schema.containsKey("$ref")) return schema;
        String ref = (String) schema.get("$ref");
        assertTrue(ref.startsWith("#/"), "local references only");
        Object value = root;
        for (String key : ref.substring(2).split("/")) value = map(value).get(key);
        return map(value);
    }

    private static Map<?, ?> map(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<?, ?>) value;
    }

    private static void validate(Map<?, ?> root, Map<?, ?> raw, Object value, String path) {
        var schema = resolve(root, raw);
        Object type = schema.get("type");
        if (type instanceof List<?> types) {
            if (value == null) { assertTrue(types.contains("null"), path); return; }
            type = types.stream().filter(t -> !"null".equals(t)).findFirst().orElseThrow();
        }
        if (schema.get("enum") instanceof List<?> values) assertTrue(values.contains(value), path);
        switch (String.valueOf(type)) {
            case "object" -> {
                var object = map(value);
                var properties = map(schema.get("properties"));
                if (schema.get("required") instanceof List<?> required)
                    for (Object key : required) assertTrue(object.containsKey(key), path+" missing "+key);
                // Explicitly reject undeclared response fields even for open response schemas.
                for (var entry : object.entrySet()) {
                    assertTrue(properties.containsKey(entry.getKey()), path+" unexpected "+entry.getKey());
                    validate(root, map(properties.get(entry.getKey())), entry.getValue(), path+"."+entry.getKey());
                }
            }
            case "array" -> {
                assertInstanceOf(List.class, value, path);
                var items = (List<?>) value;
                if (schema.get("maxItems") instanceof Number n) assertTrue(items.size() <= n.intValue(), path);
                if (schema.get("minItems") instanceof Number n) assertTrue(items.size() >= n.intValue(), path);
                for (Object item : items) validate(root, map(schema.get("items")), item, path+"[]");
            }
            case "string" -> {
                assertInstanceOf(String.class, value, path);
                String text = (String) value;
                if ("uuid".equals(schema.get("format"))) UUID.fromString(text);
                if ("date-time".equals(schema.get("format"))) OffsetDateTime.parse(text);
                if (schema.get("pattern") instanceof String regex) assertTrue(Pattern.compile(regex).matcher(text).find(), path);
                if (schema.get("minLength") instanceof Number n) assertTrue(text.codePointCount(0, text.length()) >= n.intValue(), path);
                if (schema.get("maxLength") instanceof Number n)
                    assertTrue(text.codePointCount(0, text.length()) <= n.intValue(), path);
            }
            case "integer" -> {
                assertTrue(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger, path);
                if (schema.get("minimum") instanceof Number n) assertTrue(((Number) value).longValue() >= n.longValue(), path);
                if (schema.get("maximum") instanceof Number n) assertTrue(((Number) value).longValue() <= n.longValue(), path);
            }
            case "boolean" -> assertInstanceOf(Boolean.class, value, path);
            default -> fail("unsupported contract type "+type+" at "+path);
        }
    }
}
