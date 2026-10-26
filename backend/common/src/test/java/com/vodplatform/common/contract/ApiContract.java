package com.vodplatform.common.contract;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.OpenApi30;
import com.networknt.schema.keyword.AnnotationKeyword;
import com.networknt.schema.keyword.NonValidationKeyword;
import com.networknt.schema.path.PathType;
import com.networknt.schema.serialization.JsonMapperFactory;
import com.networknt.schema.serialization.YamlMapperFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frozen public API contract, {@code docs/architecture/API-CONTRACT.yaml}, read from the
 * repository (never a copy) to check real HTTP responses in tests (Issue #120).
 *
 * <p>A response conforms when the contract declares its path and method, declares its status
 * (exactly, as a range such as {@code 4XX}, or as {@code default}), and the body matches the
 * declared content: an empty body when the response declares none; otherwise a declared media
 * type and, for JSON, a body that is valid against the response schema with OpenAPI 3.0 semantics
 * (required, type, nullable, enum, format and the other schema keywords, through the networknt
 * json-schema-validator OpenAPI 3.0 dialect with format assertions enabled).
 *
 * <p>One rule is stricter than plain JSON Schema: a response object may carry only the properties
 * its schema declares. OpenAPI leaves {@code additionalProperties} open by default, but in a
 * contract-first API an undocumented field is drift, so the check applies unless the schema sets
 * {@code additionalProperties} itself.
 *
 * <p>Requests are not validated: tests send invalid requests on purpose.
 */
public final class ApiContract {

    /** Location of the contract, relative to the repository root. */
    public static final Path CONTRACT_PATH = Path.of("docs", "architecture", "API-CONTRACT.yaml");

    // Synthetic identifiers: the validator only reads the contract text mapped to them and never
    // fetches anything (remote loading is off by default; .invalid is a reserved top-level domain).
    private static final String CONTRACT_IRI = "https://vod-platform.invalid/docs/architecture/API-CONTRACT.yaml";
    private static final String RESPONSE_SCHEMA_IRI = "https://vod-platform.invalid/api-contract/response-schema/";
    private static final String DIALECT_ID = "https://vod-platform.invalid/api-contract/openapi-3.0";

    /** Fields of the OpenAPI document root, which is loaded as the container of the schemas. */
    private static final Set<String> DOCUMENT_FIELDS = Set.of(
            "openapi", "info", "servers", "paths", "components", "security", "tags", "externalDocs");
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final JsonNode document;
    private final List<PathTemplate> pathTemplates;
    private final SchemaRegistry schemaRegistry;
    private final Map<String, Schema> compiledSchemas = new ConcurrentHashMap<>();
    private final AtomicInteger schemaCount = new AtomicInteger();

    private ApiContract(String yaml) {
        this.document = YamlMapperFactory.getInstance().readTree(yaml);
        this.pathTemplates = this.document.path("paths").propertyNames().stream()
                .map(PathTemplate::new)
                .toList();
        // OpenAPI 3.0 schema keywords only: an unknown keyword (for example a misspelled
        // "nullable") would otherwise be ignored and silently weaken the validation. The boolean
        // exclusiveMinimum/exclusiveMaximum modifiers are read by the minimum/maximum keywords, as
        // in draft 4, and specification extensions (x-...) are annotations.
        Dialect dialect = Dialect.builder(DIALECT_ID, OpenApi30.getInstance())
                .keyword(new NonValidationKeyword("exclusiveMinimum"))
                .keyword(new NonValidationKeyword("exclusiveMaximum"))
                .unknownKeywordFactory((keyword, schemaContext) -> {
                    if (DOCUMENT_FIELDS.contains(keyword) || keyword.startsWith("x-")) {
                        return new AnnotationKeyword(keyword);
                    }
                    throw new SchemaException("The contract uses the schema keyword '" + keyword
                            + "', which the OpenAPI 3.0 schema object does not define");
                })
                .build();
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .pathType(PathType.JSON_PATH)
                .locale(Locale.ENGLISH)
                .build();
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(dialect, registry -> registry
                .schemas(Map.of(CONTRACT_IRI, yaml))
                .schemaRegistryConfig(config));
    }

    /** The contract of this repository. */
    public static ApiContract get() {
        return Holder.INSTANCE;
    }

    /** A contract parsed from the given YAML text; tests of the validator itself use it. */
    static ApiContract parse(String yaml) {
        return new ApiContract(yaml);
    }

    /** The violations of the response in the result; empty when it conforms. */
    public List<String> violations(MvcResult result) {
        MockHttpServletRequest request = result.getRequest();
        MockHttpServletResponse response = result.getResponse();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return violations(request.getMethod(), path, response.getStatus(), response.getContentType(),
                response.getContentAsByteArray());
    }

    /** The violations of a response; empty when it conforms. */
    public List<String> violations(String method, String path, int status, String contentType, byte[] body) {
        Optional<PathTemplate> template = pathTemplates.stream()
                .filter(candidate -> candidate.matches(path))
                .max(Comparator.comparingInt(PathTemplate::literalSegments));
        if (template.isEmpty()) {
            return List.of("path " + path + " is not declared");
        }
        String operationPointer = pointer("paths", template.get().template(), method.toLowerCase(Locale.ROOT));
        JsonNode operation = document.at(operationPointer);
        if (!operation.isObject()) {
            return List.of("operation " + method + " " + template.get().template() + " is not declared");
        }
        Optional<String> statusKey = Optional.of(Integer.toString(status))
                .filter(operation.path("responses")::has)
                .or(() -> Optional.of(status / 100 + "XX").filter(operation.path("responses")::has))
                .or(() -> Optional.of("default").filter(operation.path("responses")::has));
        if (statusKey.isEmpty()) {
            return List.of("status " + status + " is not declared for " + method + " " + template.get().template());
        }
        JsonNode declaredResponse = resolve(operation.path("responses").path(statusKey.get()));
        JsonNode content = declaredResponse.path("content");
        boolean hasBody = body != null && body.length > 0;
        if (content.isMissingNode() || content.isEmpty()) {
            return hasBody ? List.of("status " + status + " declares no body, but the response has one") : List.of();
        }
        if (!hasBody) {
            return List.of("status " + status + " declares a body, but the response is empty");
        }
        Optional<String> mediaType = declaredMediaType(content, contentType);
        if (mediaType.isEmpty()) {
            return List.of("content type " + contentType + " is not declared for status " + status
                    + " (declared: " + String.join(", ", content.propertyNames()) + ")");
        }
        JsonNode schemaNode = content.path(mediaType.get()).path("schema");
        if (schemaNode.isMissingNode() || !isJson(mediaType.get())) {
            // Non-JSON bodies such as HLS playlists are checked by their own tests.
            return List.of();
        }
        JsonNode instance;
        try {
            instance = JsonMapperFactory.getInstance().readTree(body);
        } catch (JacksonException exception) {
            return List.of("the response body is not valid JSON");
        }
        String schemaKey = method + " " + template.get().template() + " " + statusKey.get() + " " + mediaType.get();
        List<String> violations = new ArrayList<>();
        for (Error error : compile(schemaKey, schemaNode).validate(instance)) {
            violations.add(error.toString());
        }
        collectUndeclaredProperties(schemaNode, instance, "$", violations);
        return violations;
    }

    /**
     * Compiles every response schema of the contract and returns the number compiled, so a broken
     * reference or an unknown keyword fails even before a test reaches that response.
     */
    int compileAllResponseSchemas() {
        int count = 0;
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                for (Map.Entry<String, JsonNode> response : operation.getValue().path("responses").properties()) {
                    for (Map.Entry<String, JsonNode> media : resolve(response.getValue()).path("content").properties()) {
                        JsonNode schemaNode = media.getValue().path("schema");
                        if (!schemaNode.isMissingNode() && isJson(media.getKey())) {
                            compile(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey() + " "
                                    + response.getKey() + " " + media.getKey(), schemaNode);
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private Schema compile(String key, JsonNode schemaNode) {
        return compiledSchemas.computeIfAbsent(key, ignored -> {
            Schema schema = schemaRegistry.getSchema(
                    SchemaLocation.of(RESPONSE_SCHEMA_IRI + schemaCount.incrementAndGet()),
                    withAbsoluteReferences(schemaNode));
            schema.initializeValidators();
            return schema;
        });
    }

    /**
     * Reports object properties that the schema does not declare. Properties of all allOf, oneOf
     * and anyOf branches count as declared. A schema that sets additionalProperties, or declares no
     * properties at all, is left to the JSON Schema validation.
     */
    private void collectUndeclaredProperties(JsonNode schemaNode, JsonNode value, String location,
            List<String> violations) {
        List<JsonNode> branches = new ArrayList<>();
        collectBranches(schemaNode, branches);
        if (value.isObject()) {
            if (branches.stream().anyMatch(branch -> branch.has("additionalProperties"))
                    || branches.stream().noneMatch(branch -> branch.has("properties"))) {
                return;
            }
            Map<String, List<JsonNode>> declared = new LinkedHashMap<>();
            for (JsonNode branch : branches) {
                for (Map.Entry<String, JsonNode> property : branch.path("properties").properties()) {
                    declared.computeIfAbsent(property.getKey(), name -> new ArrayList<>()).add(property.getValue());
                }
            }
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                String fieldLocation = location + "." + field.getKey();
                List<JsonNode> propertySchemas = declared.get(field.getKey());
                if (propertySchemas == null) {
                    violations.add(fieldLocation + ": property is not declared by the response schema");
                } else {
                    for (JsonNode propertySchema : propertySchemas) {
                        collectUndeclaredProperties(propertySchema, field.getValue(), fieldLocation, violations);
                    }
                }
            }
        } else if (value.isArray()) {
            for (JsonNode branch : branches) {
                JsonNode items = branch.get("items");
                if (items != null) {
                    for (int index = 0; index < value.size(); index++) {
                        collectUndeclaredProperties(items, value.get(index), location + "[" + index + "]", violations);
                    }
                }
            }
        }
    }

    private void collectBranches(JsonNode schemaNode, List<JsonNode> branches) {
        JsonNode schema = resolve(schemaNode);
        branches.add(schema);
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            for (JsonNode branch : schema.path(composition)) {
                collectBranches(branch, branches);
            }
        }
    }

    /** Follows local references such as {@code #/components/responses/Unauthorized}. */
    private JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        for (int depth = 0; current.has("$ref"); depth++) {
            String reference = current.path("$ref").asString();
            if (!reference.startsWith("#/") || depth > 20) {
                throw new IllegalStateException(CONTRACT_PATH + ": unsupported reference " + reference);
            }
            current = document.at(reference.substring(1));
            if (current.isMissingNode()) {
                throw new IllegalStateException(CONTRACT_PATH + ": unresolved reference " + reference);
            }
        }
        return current;
    }

    /** Points local references at the contract document, so an inline schema resolves them too. */
    private static JsonNode withAbsoluteReferences(JsonNode schemaNode) {
        JsonNode copy = schemaNode.deepCopy();
        makeReferencesAbsolute(copy);
        return copy;
    }

    private static void makeReferencesAbsolute(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode reference = object.get("$ref");
            if (reference != null && reference.isString() && reference.asString().startsWith("#")) {
                object.put("$ref", CONTRACT_IRI + reference.asString());
            }
        }
        for (JsonNode child : node) {
            makeReferencesAbsolute(child);
        }
    }

    private static Optional<String> declaredMediaType(JsonNode content, String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        MediaType actual;
        try {
            actual = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException exception) {
            return Optional.empty();
        }
        return content.propertyNames().stream()
                .filter(declared -> MediaType.parseMediaType(declared).isCompatibleWith(actual))
                .findFirst();
    }

    private static boolean isJson(String mediaType) {
        MediaType type = MediaType.parseMediaType(mediaType);
        return MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.getSubtype().endsWith("+json");
    }

    private static String pointer(String... tokens) {
        StringBuilder pointer = new StringBuilder();
        for (String token : tokens) {
            pointer.append('/').append(token.replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }

    private static Path locateContract() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(CONTRACT_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find " + CONTRACT_PATH + " in " + Path.of("").toAbsolutePath()
                + " or its parent directories");
    }

    /** A path of the contract; a {@code {parameter}} segment matches any non-empty segment. */
    private record PathTemplate(String template, List<String> segments) {

        PathTemplate(String template) {
            this(template, List.of(template.split("/", -1)));
        }

        boolean matches(String path) {
            String[] actual = path.split("/", -1);
            if (actual.length != segments.size()) {
                return false;
            }
            for (int index = 0; index < actual.length; index++) {
                String segment = segments.get(index);
                if (isParameter(segment) ? actual[index].isEmpty() : !segment.equals(actual[index])) {
                    return false;
                }
            }
            return true;
        }

        /** OpenAPI matches concrete paths before templated ones, so more literal segments win. */
        int literalSegments() {
            return (int) segments.stream().filter(segment -> !isParameter(segment)).count();
        }

        private static boolean isParameter(String segment) {
            return segment.startsWith("{") && segment.endsWith("}");
        }
    }

    private static final class Holder {

        private static final ApiContract INSTANCE = load();

        private static ApiContract load() {
            Path contract = locateContract();
            try {
                return new ApiContract(Files.readString(contract, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read " + contract, exception);
            }
        }
    }
}
