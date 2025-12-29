package me.bechberger.jthreaddump.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Helper class for asserting JSON structures in tests
 */
public class JsonAssertions {

    private static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /**
     * Parse JSON string into JsonNode
     */
    public static JsonNode parseJson(String json) throws IOException {
        return mapper.readTree(json);
    }

    /**
     * Assert that JSON contains a field with expected value
     */
    public static void assertJsonField(JsonNode json, String fieldPath, Object expectedValue) {
        JsonNode node = navigateToField(json, fieldPath);
        assertNotNull(node, "Field not found: " + fieldPath);

        if (expectedValue instanceof String) {
            assertEquals(expectedValue, node.asText(), "Field value mismatch at: " + fieldPath);
        } else if (expectedValue instanceof Integer) {
            assertEquals(expectedValue, node.asInt(), "Field value mismatch at: " + fieldPath);
        } else if (expectedValue instanceof Long) {
            assertEquals(expectedValue, node.asLong(), "Field value mismatch at: " + fieldPath);
        } else if (expectedValue instanceof Boolean) {
            assertEquals(expectedValue, node.asBoolean(), "Field value mismatch at: " + fieldPath);
        } else {
            fail("Unsupported expected value type: " + expectedValue.getClass());
        }
    }

    /**
     * Assert that JSON field exists
     */
    public static void assertJsonFieldExists(JsonNode json, String fieldPath) {
        JsonNode node = navigateToField(json, fieldPath);
        assertNotNull(node, "Field not found: " + fieldPath);
    }

    /**
     * Assert that JSON field does not exist
     */
    public static void assertJsonFieldNotExists(JsonNode json, String fieldPath) {
        JsonNode node = navigateToField(json, fieldPath);
        assertTrue(node == null || node.isNull(), "Field should not exist: " + fieldPath);
    }

    /**
     * Assert that JSON array has expected size
     */
    public static void assertJsonArraySize(JsonNode json, String arrayPath, int expectedSize) {
        JsonNode node = navigateToField(json, arrayPath);
        assertNotNull(node, "Array not found: " + arrayPath);
        assertTrue(node.isArray(), "Field is not an array: " + arrayPath);
        assertEquals(expectedSize, node.size(), "Array size mismatch at: " + arrayPath);
    }

    /**
     * Assert that JSON array contains an element matching predicate
     */
    public static void assertJsonArrayContains(JsonNode json, String arrayPath,
                                                String elementField, Object expectedValue) {
        JsonNode node = navigateToField(json, arrayPath);
        assertNotNull(node, "Array not found: " + arrayPath);
        assertTrue(node.isArray(), "Field is not an array: " + arrayPath);

        boolean found = false;
        for (JsonNode element : node) {
            JsonNode field = element.get(elementField);
            if (field != null) {
                if (expectedValue instanceof String && field.asText().equals(expectedValue)) {
                    found = true;
                    break;
                } else if (expectedValue instanceof Integer && field.asInt() == (Integer) expectedValue) {
                    found = true;
                    break;
                } else if (expectedValue instanceof Long && field.asLong() == (Long) expectedValue) {
                    found = true;
                    break;
                } else if (expectedValue instanceof Boolean && field.asBoolean() == (Boolean) expectedValue) {
                    found = true;
                    break;
                }
            }
        }

        assertTrue(found, String.format("Array %s does not contain element with %s=%s",
                arrayPath, elementField, expectedValue));
    }

    /**
     * Assert that entire JSON structure matches expected JSON string
     */
    public static void assertJsonEquals(String expectedJson, String actualJson) throws IOException {
        JsonNode expected = parseJson(expectedJson);
        JsonNode actual = parseJson(actualJson);
        assertEquals(expected, actual, "JSON structures do not match");
    }

    /**
     * Assert that JSON matches expected structure (partial match)
     */
    public static void assertJsonMatches(JsonNode actual, String expectedJsonString) throws IOException {
        JsonNode expected = parseJson(expectedJsonString);
        assertJsonNodeMatches(expected, actual, "");
    }

    private static void assertJsonNodeMatches(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertTrue(actual.isObject(), "Expected object at: " + path);
            expected.fieldNames().forEachRemaining(fieldName -> {
                String fieldPath = path.isEmpty() ? fieldName : path + "." + fieldName;
                JsonNode expectedField = expected.get(fieldName);
                JsonNode actualField = actual.get(fieldName);
                assertNotNull(actualField, "Missing field at: " + fieldPath);
                assertJsonNodeMatches(expectedField, actualField, fieldPath);
            });
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), "Expected array at: " + path);
            assertEquals(expected.size(), actual.size(), "Array size mismatch at: " + path);
            for (int i = 0; i < expected.size(); i++) {
                assertJsonNodeMatches(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        } else {
            assertEquals(expected, actual, "Value mismatch at: " + path);
        }
    }

    /**
     * Navigate to a field using dot notation (e.g., "threads.0.name")
     */
    private static JsonNode navigateToField(JsonNode root, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        JsonNode current = root;

        for (String part : parts) {
            if (current == null) {
                return null;
            }

            // Check if accessing array element
            if (part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                if (current.isArray() && index < current.size()) {
                    current = current.get(index);
                } else {
                    return null;
                }
            } else {
                current = current.get(part);
            }
        }

        return current;
    }

    /**
     * Get a JsonNode at path for further inspection
     */
    public static JsonNode getJsonNode(JsonNode root, String fieldPath) {
        return navigateToField(root, fieldPath);
    }

    /**
     * Count elements in JSON array matching a condition
     */
    public static int countJsonArrayElements(JsonNode json, String arrayPath,
                                              String elementField, Object expectedValue) {
        JsonNode node = navigateToField(json, arrayPath);
        if (node == null || !node.isArray()) {
            return 0;
        }

        int count = 0;
        for (JsonNode element : node) {
            JsonNode field = element.get(elementField);
            if (field != null) {
                if (expectedValue instanceof String && field.asText().equals(expectedValue)) {
                    count++;
                } else if (expectedValue instanceof Integer && field.asInt() == (Integer) expectedValue) {
                    count++;
                } else if (expectedValue instanceof Boolean && field.asBoolean() == (Boolean) expectedValue) {
                    count++;
                }
            }
        }

        return count;
    }
}