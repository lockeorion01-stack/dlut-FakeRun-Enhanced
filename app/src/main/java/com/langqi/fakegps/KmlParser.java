package com.langqi.fakegps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Bounded, namespace-aware parsing shared by built-in and imported routes. */
final class KmlParser {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_POINTS = 50000;

    private KmlParser() { }

    static List<GeoUtils.TrackPoint> read(InputStream input) throws Exception {
        return readPrepared(input).points;
    }

    static PreparedRoute readPrepared(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_BYTES) throw new IOException("KML 文件不能超过 5 MB");
            bytes.write(buffer, 0, count);
        }
        byte[] data = bytes.toByteArray();
        // Also reject declarations on Android parsers without the disallow-doctype feature.
        // Removing zero bytes covers UTF-16/32 as well as ASCII-compatible XML encodings.
        String declarations = new String(data, StandardCharsets.ISO_8859_1)
                .replace("\u0000", "").toUpperCase(Locale.ROOT);
        if (declarations.contains("<!DOCTYPE") || declarations.contains("<!ENTITY")) {
            throw new IllegalArgumentException("KML 不支持 DTD 或实体声明");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        feature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        feature(factory, "http://xml.org/sax/features/external-general-entities", false);
        feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("不允许外部实体"); });
        Document document = builder.parse(new ByteArrayInputStream(data));
        NodeList lines = document.getElementsByTagNameNS("*", "LineString");
        if (lines.getLength() > 1) {
            throw new IllegalArgumentException("文件包含多条路线，请分别导出后导入");
        }
        NodeList coordinates = lines.getLength() == 1
                ? ((Element) lines.item(0)).getElementsByTagNameNS("*", "coordinates")
                : document.getElementsByTagNameNS("*", "coordinates");
        if (coordinates.getLength() == 0) throw new IllegalArgumentException("未找到路线坐标");
        String text = coordinates.item(0).getTextContent().trim();
        List<GeoUtils.TrackPoint> points = new ArrayList<>();
        Matcher tuples = Pattern.compile("\\S+").matcher(text);
        while (tuples.find()) {
            if (points.size() >= MAX_POINTS) throw new IllegalArgumentException("路线最多支持 50000 个坐标点");
            String tuple = tuples.group();
            String[] values = tuple.split(",", -1);
            if (values.length < 2 || values.length > 3) throw new IllegalArgumentException("坐标格式无效");
            double longitude = Double.parseDouble(values[0]);
            double latitude = Double.parseDouble(values[1]);
            double altitude = values.length == 3 && !values[2].isEmpty()
                    ? Double.parseDouble(values[2]) : 0;
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(altitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("坐标或高程无效");
            }
            points.add(new GeoUtils.TrackPoint(latitude, longitude, altitude));
        }
        return new PreparedRoute(points);
    }

    private static void feature(DocumentBuilderFactory factory, String name, boolean enabled) {
        try {
            factory.setFeature(name, enabled);
        } catch (Exception | AbstractMethodError ignored) {
            // Android XML implementations vary; bounded input and declaration rejection still apply.
        }
    }
}
