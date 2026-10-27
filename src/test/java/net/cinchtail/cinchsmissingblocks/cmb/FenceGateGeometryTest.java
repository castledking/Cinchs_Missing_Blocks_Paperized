package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class FenceGateGeometryTest {
    private final JsonObject vanilla = JsonParser.parseString(read(
            Path.of("src/test/resources/vanilla-fence-gate-geometry.json"))).getAsJsonObject();

    @Test
    void everyGateStateCoversTheUnderlyingVanillaGeometry() {
        Map<?, ?> blocks = (Map<?, ?>) yaml("pack/configuration/blocks/fallback_double.yml").get("blocks");
        Map<?, ?> items = (Map<?, ?>) yaml("pack/configuration/items.yml").get("items");
        int gates = 0;
        for (Map.Entry<?, ?> entry : blocks.entrySet()) {
            if (!entry.getKey().toString().endsWith("_fence_gate")) continue;
            gates++;
            Map<?, ?> states = (Map<?, ?>) ((Map<?, ?>) entry.getValue()).get("states");
            Map<?, ?> appearances = (Map<?, ?>) states.get("appearances");
            assertEquals(32, appearances.size(), entry.getKey().toString());
            for (Map.Entry<?, ?> state : appearances.entrySet()) {
                String label = entry.getKey() + "[" + state.getKey() + "]";
                String visualState = state.getKey().toString().replaceAll(",powered=(true|false)", "");
                JsonObject reference = vanilla.getAsJsonObject("variants").getAsJsonObject(visualState);
                List<Box> expected = boxes(reference.get("model").getAsString(),
                        reference.has("y") ? reference.get("y").getAsInt() : 0,
                        new double[] {1, 1, 1}, new double[] {0, 0, 0});
                Map<?, ?> appearance = (Map<?, ?>) state.getValue();
                assertEquals("minecraft:warped_fence_gate[" + state.getKey() + "]",
                        appearance.get("state"), label);
                List<Box> actual = new ArrayList<>();
                for (Object value : (List<?>) appearance.get("entity_renderer")) {
                    Map<?, ?> renderer = (Map<?, ?>) value;
                    Map<?, ?> item = (Map<?, ?>) items.get(renderer.get("item"));
                    String model = ((Map<?, ?>) item.get("model")).get("path").toString();
                    assertNoCoplanarFaces(elements(model), label);
                    double[] offset = vector(renderer.get("translation"), 0);
                    double[] position = vector(renderer.get("position"), 0.5);
                    for (int axis = 0; axis < 3; axis++) offset[axis] = 16 * (offset[axis] + position[axis] - 0.5);
                    // An item display's model faces backwards relative to block models.
                    int rotation = ((Number) renderer.get("rotation")).intValue() - 180;
                    actual.addAll(boxes(model, rotation, vector(renderer.get("scale"), 1), offset));
                }
                for (Box target : expected) {
                    assertTrue(actual.stream().anyMatch(cover -> cover.covers(target)),
                            () -> "Exposed vanilla gate geometry: " + label + " " + target);
                }
                for (Box rendered : actual) {
                    for (int axis = 0; axis < 3; axis++) {
                        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                        for (Box original : expected) {
                            low = Math.min(low, original.low[axis]);
                            high = Math.max(high, original.high[axis]);
                        }
                        assertTrue(rendered.low[axis] >= low - 0.3 && rendered.high[axis] <= high + 0.3,
                                "Oversized gate geometry: " + label);
                    }
                }
            }
        }
        assertEquals(6, gates);
    }

    private static void assertNoCoplanarFaces(JsonArray elements, String label) {
        for (int i = 0; i < elements.size(); i++) {
            JsonObject a = elements.get(i).getAsJsonObject();
            for (int j = i + 1; j < elements.size(); j++) {
                JsonObject b = elements.get(j).getAsJsonObject();
                for (String face : List.of("west", "east", "down", "up", "north", "south")) {
                    if (!a.has("faces") || !b.has("faces")
                            || !a.getAsJsonObject("faces").has(face)
                            || !b.getAsJsonObject("faces").has(face)) continue;
                    int axis = switch (face) {
                        case "west", "east" -> 0;
                        case "down", "up" -> 1;
                        default -> 2;
                    };
                    String bound = List.of("west", "down", "north").contains(face) ? "from" : "to";
                    if (Math.abs(a.getAsJsonArray(bound).get(axis).getAsDouble()
                            - b.getAsJsonArray(bound).get(axis).getAsDouble()) > 0.000001) continue;
                    boolean overlaps = true;
                    for (int side = 0; side < 3; side++) {
                        if (side == axis) continue;
                        double low = Math.max(a.getAsJsonArray("from").get(side).getAsDouble(),
                                b.getAsJsonArray("from").get(side).getAsDouble());
                        double high = Math.min(a.getAsJsonArray("to").get(side).getAsDouble(),
                                b.getAsJsonArray("to").get(side).getAsDouble());
                        overlaps &= high - low > 0.000001;
                    }
                    assertTrue(!overlaps, "Coplanar overlapping gate faces: " + label
                            + " elements " + i + ", " + j + " face=" + face);
                }
            }
        }
    }

    private List<Box> boxes(String model, int rotation, double[] scale, double[] offset) {
        JsonArray elements = elements(model);
        List<Box> result = new ArrayList<>();
        for (var value : elements) {
            JsonObject element = value.getAsJsonObject();
            double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double[] point = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    String bound = (corner & (1 << axis)) == 0 ? "from" : "to";
                    point[axis] = (element.getAsJsonArray(bound).get(axis).getAsDouble() - 8) * scale[axis];
                }
                for (int turn = 0; turn < Math.floorMod(rotation, 360) / 90; turn++) {
                    double x = point[0];
                    point[0] = -point[2];
                    point[2] = x;
                }
                for (int axis = 0; axis < 3; axis++) {
                    double coordinate = point[axis] + 8 + offset[axis];
                    low[axis] = Math.min(low[axis], coordinate);
                    high[axis] = Math.max(high[axis], coordinate);
                }
            }
            result.add(new Box(low, high));
        }
        return result;
    }

    private JsonArray elements(String ref) {
        String[] parts = ref.split(":", 2);
        Path file = Path.of("pack/resourcepack/assets", parts[0], "models", parts[1] + ".json");
        JsonObject model = Files.isRegularFile(file) ? JsonParser.parseString(read(file)).getAsJsonObject()
                : vanilla.getAsJsonObject("models").getAsJsonObject(ref);
        assertTrue(model != null, "Missing model: " + ref);
        return model.has("elements") ? model.getAsJsonArray("elements") : elements(model.get("parent").getAsString());
    }

    private static Map<?, ?> yaml(String file) {
        return new Yaml().loadAs(read(Path.of(file)), Map.class);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static double[] vector(Object value, double fallback) {
        if (value == null) return new double[] {fallback, fallback, fallback};
        String[] parts = value.toString().split(",");
        return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
    }

    private record Box(double[] low, double[] high) {
        boolean covers(Box other) {
            for (int axis = 0; axis < 3; axis++) {
                // Leave at least an eighth of a pixel between the two rendered surfaces.
                double lowerMargin = other.low[axis] - low[axis];
                double upperMargin = high[axis] - other.high[axis];
                if (lowerMargin < 0.125 || upperMargin < 0.125) return false;
            }
            return true;
        }

        @Override public String toString() {
            return java.util.Arrays.toString(low) + ".." + java.util.Arrays.toString(high);
        }
    }
}
