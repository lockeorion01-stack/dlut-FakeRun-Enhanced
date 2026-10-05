package com.langqi.fakegps;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class RouteRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private RouteRepository repository() throws IOException {
        return new RouteRepository(temporary.newFolder("routes"));
    }

    @Test public void importPublishesOnlyTheDurableFile() throws Exception {
        RouteRepository repository = repository();
        File saved = repository.save(KmlParserTest.input(KmlParserTest.VALID), "example.kml", null);
        assertEquals(1, repository.files().size());
        assertEquals(saved, repository.files().get(0));
        assertEquals(1, saved.getParentFile().list().length);
    }

    @Test public void stagingFileIsNeverListedDuringImport() throws Exception {
        RouteRepository repository = repository();
        InputStream source = new java.io.FilterInputStream(KmlParserTest.input(KmlParserTest.VALID)) {
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                assertTrue(repository.files().isEmpty());
                return super.read(bytes, offset, count);
            }
        };
        repository.save(source, "route.kml", null);
        assertEquals(1, repository.files().size());
    }

    @Test public void invalidReplacementPreservesOriginalBytes() throws Exception {
        RouteRepository repository = repository();
        File original = repository.save(KmlParserTest.input(KmlParserTest.VALID), "route.kml", null);
        byte[] before = Files.readAllBytes(original.toPath());
        assertThrows(Exception.class,
                () -> repository.save(KmlParserTest.input("<kml/>"), "route.kml", original));
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
        assertEquals(1, original.getParentFile().list().length);
    }

    @Test public void interruptedCopyPreservesOriginalAndCleansStagingFile() throws Exception {
        RouteRepository repository = repository();
        File original = repository.save(KmlParserTest.input(KmlParserTest.VALID), "route.kml", null);
        byte[] before = Files.readAllBytes(original.toPath());
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Storage failure"); }
        };
        assertThrows(IOException.class, () -> repository.save(failing, "route.kml", original));
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
        assertEquals(1, original.getParentFile().list().length);
    }

    @Test public void validReplacementChangesTheSameFile() throws Exception {
        RouteRepository repository = repository();
        File original = repository.save(KmlParserTest.input(KmlParserTest.VALID), "route.kml", null);
        String updated = KmlParserTest.VALID.replace("22.001", "22.002");
        assertEquals(original, repository.save(KmlParserTest.input(updated), "different.kml", original));
        assertArrayEquals(updated.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(original.toPath()));
        assertEquals(1, repository.files().size());
    }

    @Test public void duplicateNamesCreateSeparateRoutes() throws Exception {
        RouteRepository repository = repository();
        File first = repository.save(KmlParserTest.input(KmlParserTest.VALID), "route.kml", null);
        File second = repository.save(KmlParserTest.input(KmlParserTest.VALID), "route.kml", null);
        assertNotEquals(first, second);
        assertEquals(2, repository.files().size());
    }
}
