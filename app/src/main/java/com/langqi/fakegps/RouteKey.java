package com.langqi.fakegps;

/** Stable persisted route identifiers, including migration from the version 1 resource table. */
final class RouteKey {
    private RouteKey() { }

    static String builtIn(String resourceName) {
        return "raw:" + resourceName;
    }

    static String migrate(String saved) {
        if (saved == null || !saved.startsWith("raw:")) return saved;
        final int legacyId;
        try {
            legacyId = Integer.parseInt(saved.substring(4));
        } catch (NumberFormatException e) {
            return saved; // Already a stable resource name.
        }
        // Frozen IDs from v1 (8a42c1e), identical in its Debug and Release R.txt.
        // Never resolve these against the NEW resource table: IDs may now refer to other routes.
        switch (legacyId) {
            case 0x7f0f0000: return builtIn("asean_route_1");
            case 0x7f0f0001: return builtIn("asean_route_2");
            case 0x7f0f0002: return builtIn("asean_route_3");
            case 0x7f0f0003: return builtIn("asean_route_4");
            case 0x7f0f0004: return builtIn("asean_route_5");
            case 0x7f0f0005: return builtIn("three_km");
            case 0x7f0f0006: return builtIn("uni_route_1");
            case 0x7f0f0007: return builtIn("uni_route_2");
            case 0x7f0f0008: return builtIn("uni_route_3");
            case 0x7f0f0009: return builtIn("uni_route_4");
            case 0x7f0f000a: return builtIn("uni_route_5");
            case 0x7f0f000b: return builtIn("uni_route_6");
            default: return null; // Unknown old builds fall back instead of selecting a wrong route.
        }
    }
}
