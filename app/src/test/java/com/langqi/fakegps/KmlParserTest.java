package com.langqi.fakegps;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class KmlParserTest {
    static final String VALID = "<kml xmlns='http://www.opengis.net/kml/2.2'><Document>"
            + "<Placemark><Point><coordinates>0,0,0</coordinates></Point></Placemark>"
            + "<Placemark><LineString><coordinates>108,22,10 108,22.001,20</coordinates>"
            + "</LineString></Placemark></Document></kml>";

    static ByteArrayInputStream input(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void routeTakesPriorityOverPointLandmarks() throws Exception {
        List<GeoUtils.TrackPoint> points = KmlParser.read(input(VALID));
        assertEquals(2, points.size());
        assertEquals(108, points.get(0).longitude, 0);
        assertEquals(20, points.get(1).altitude, 0);
    }

    @Test public void prefixedNamespacesAndMissingAltitudeAreSupported() throws Exception {
        String kml = "<k:kml xmlns:k='http://www.opengis.net/kml/2.2'><k:LineString>"
                + "<k:coordinates>108,22 108,22.001</k:coordinates></k:LineString></k:kml>";
        assertEquals(0, KmlParser.read(input(kml)).get(0).altitude, 0);
    }

    @Test public void multiplePathsAreRejectedInsteadOfSilentlyDroppingOne() {
        String two = VALID.replace("</Document>", "<LineString><coordinates>1,1 1,2</coordinates>"
                + "</LineString></Document>");
        assertThrows(IllegalArgumentException.class, () -> KmlParser.read(input(two)));
    }

    @Test public void invalidCoordinatesAndAltitudeAreRejected() {
        for (String coordinate : new String[]{"NaN,22,10", "108,91,10", "108,22,Infinity", "bad", "108,22,NaN"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> KmlParser.read(input(VALID.replace("108,22,10", coordinate))));
        }
    }

    @Test public void coincidentRouteIsRejectedAtImport() {
        assertThrows(IllegalArgumentException.class,
                () -> KmlParser.read(input(VALID.replace("108,22.001,20", "108,22,20"))));
    }

    @Test public void oversizedFileIsRejectedBeforeXmlParsing() {
        assertThrows(IOException.class,
                () -> KmlParser.read(new ByteArrayInputStream(new byte[KmlParser.MAX_BYTES + 1])));
    }

    @Test public void entityDeclarationsAreRejectedIncludingUtf16() {
        String entity = "<!DOCTYPE kml [<!ENTITY x SYSTEM 'file:///invalid'>]>" + VALID;
        assertThrows(IllegalArgumentException.class, () -> KmlParser.read(input(entity)));
        assertThrows(IllegalArgumentException.class, () -> KmlParser.read(new ByteArrayInputStream(
                entity.getBytes(StandardCharsets.UTF_16))));
    }

    @Test public void excessivePointCountIsRejectedWithinTheFileSizeLimit() {
        StringBuilder coordinates = new StringBuilder();
        for (int i = 0; i <= KmlParser.MAX_POINTS; i++) coordinates.append("108,22 ");
        String xml = "<kml><LineString><coordinates>" + coordinates
                + "</coordinates></LineString></kml>";
        assertTrue(xml.length() < KmlParser.MAX_BYTES);
        assertThrows(IllegalArgumentException.class, () -> KmlParser.read(input(xml)));
    }

    @Test public void allTwelveBundledRoutesRemainReadable() throws Exception {
        File directory = new File("src/main/res/raw");
        if (!directory.isDirectory()) directory = new File("app/src/main/res/raw");
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".kml"));
        assertNotNull(files);
        assertEquals(12, files.length);
        for (File file : files) {
            try (FileInputStream input = new FileInputStream(file)) {
                assertTrue(file.getName(), KmlParser.read(input).size() >= 2);
            }
        }
    }
}
