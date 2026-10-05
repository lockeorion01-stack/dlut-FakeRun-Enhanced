package com.langqi.fakegps;

import org.junit.Test;

import static org.junit.Assert.*;

public class RouteKeyTest {
    @Test public void allVersionOneSelectionsMigrateWithoutUsingCurrentResourceIds() {
        String[] expected = {"asean_route_1", "asean_route_2", "asean_route_3", "asean_route_4",
                "asean_route_5", "three_km", "uni_route_1", "uni_route_2", "uni_route_3",
                "uni_route_4", "uni_route_5", "uni_route_6"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals("raw:" + expected[i], RouteKey.migrate("raw:" + (0x7f0f0000 + i)));
        }
    }

    @Test public void stableAndImportedSelectionsSurviveRepeatedMigration() {
        for (String key : new String[]{"raw:three_km", "raw:uni_route_6", "file:my-route.kml"}) {
            assertEquals(key, RouteKey.migrate(RouteKey.migrate(key)));
        }
    }

    @Test public void unknownOldResourceIdsFallBackWithoutGuessingAnotherRoute() {
        assertNull(RouteKey.migrate("raw:123456"));
        assertNull(RouteKey.migrate(null));
    }
}
