package org.nrg.xnatx.plugins.transfer.service.impl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Direct unit tests for {@link BatchTransferServiceImpl.StreamingZipFileWriter}.
 * Lives in a separate class with no Spring context so it exercises the
 * streaming wrapper end-to-end without loading the service bean.
 */
public class StreamingZipFileWriterTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Verifies the SCANS+DICOM filter (case-insensitive), the catalog.xml exclusion, and entry contents. */
    @Test
    public void streamsDicomFilesAndExcludesCatalog() throws Exception {
        final Path source = tmp.newFolder("source").toPath();
        final Path dicomDir = Files.createDirectories(source.resolve("SCANS/1/DICOM"));
        Files.write(dicomDir.resolve("img1.dcm"), "img1-bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(dicomDir.resolve("img2.dcm"), "img2-bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(dicomDir.resolve("catalog.xml"), "<catalog/>".getBytes(StandardCharsets.UTF_8));
        // Non-DICOM file outside the DICOM dir: must be filtered out.
        Files.write(source.resolve("SCANS/1/NOTE.txt"), "nope".getBytes(StandardCharsets.UTF_8));
        // DICOM file with no SCANS ancestor: must be filtered out by the (intentional) SCANS narrowing.
        Files.write(Files.createDirectories(source.resolve("DICOM")).resolve("orphan.dcm"),
                "orphan".getBytes(StandardCharsets.UTF_8));

        try (BatchTransferServiceImpl.StreamingZipFileWriter w =
                     new BatchTransferServiceImpl.StreamingZipFileWriter(source, "exp.zip")) {
            assertEquals("exp.zip", w.getName());

            final Map<String, byte[]> entries = drain(w.getInputStream());
            w.awaitProducer(5_000L);

            assertEquals("expected exactly the two DICOM files, got " + entries.keySet(),
                    2, entries.size());
            assertArrayContains(entries, "SCANS/1/DICOM/img1.dcm", "img1-bytes");
            assertArrayContains(entries, "SCANS/1/DICOM/img2.dcm", "img2-bytes");
            assertNull("catalog.xml should be excluded",
                    entries.get("SCANS/1/DICOM/catalog.xml"));
            assertNull("NOTE.txt should be excluded (not in DICOM dir)",
                    entries.get("SCANS/1/NOTE.txt"));
            assertNull("DICOM file with no SCANS ancestor should be excluded",
                    entries.get("DICOM/orphan.dcm"));
        }
    }

    /** Calling getInputStream twice is a programmer error and throws. */
    @Test
    public void getInputStreamTwice_throws() throws Exception {
        final Path source = tmp.newFolder("source").toPath();
        try (BatchTransferServiceImpl.StreamingZipFileWriter w =
                     new BatchTransferServiceImpl.StreamingZipFileWriter(source, "x.zip")) {
            w.getInputStream();
            try {
                w.getInputStream();
                fail("expected IllegalStateException on second getInputStream()");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("more than once"));
            }
            w.awaitProducer(5_000L);
        }
    }

    /**
     * If the producer is still alive past the awaitProducer timeout AND has
     * not captured an error, awaitProducer must throw an IOException so the
     * batch can surface it as a Failed event instead of silently completing.
     */
    @Test
    public void awaitProducerTimeout_throwsIOException() throws Exception {
        final Path source = tmp.newFolder("source").toPath();
        final Path dicomDir = Files.createDirectories(source.resolve("SCANS/1/DICOM"));
        // Incompressible random bytes so Deflater.BEST_SPEED can't shrink the
        // payload below the pipe buffer — without this, the producer would
        // finish instantly and the timeout would never fire.
        final java.util.Random rng = new java.util.Random(0xBADCAFE);
        for (int i = 0; i < 16; i++) {
            final byte[] payload = new byte[32 * 1024];
            rng.nextBytes(payload);
            Files.write(dicomDir.resolve("img" + i + ".dcm"), payload);
        }

        try (BatchTransferServiceImpl.StreamingZipFileWriter w =
                     new BatchTransferServiceImpl.StreamingZipFileWriter(source, "hang.zip")) {
            w.getInputStream();   // start producer; intentionally never read
            try {
                w.awaitProducer(100L);
                fail("expected IOException after awaitProducer timeout");
            } catch (IOException expected) {
                assertTrue("expected timeout message, got: " + expected.getMessage(),
                        expected.getMessage().contains("did not finish within"));
            }
        }
    }

    /** Producer error (non-existent source dir) surfaces through awaitProducer. */
    @Test
    public void producerError_surfacesThroughAwaitProducer() throws Exception {
        final Path missing = tmp.getRoot().toPath().resolve("does-not-exist");
        try (BatchTransferServiceImpl.StreamingZipFileWriter w =
                     new BatchTransferServiceImpl.StreamingZipFileWriter(missing, "x.zip")) {
            // Drain whatever comes out (likely just the zip header / nothing).
            try (InputStream in = w.getInputStream()) {
                final byte[] buf = new byte[4096];
                while (in.read(buf) > 0) { /* drain */ }
            }
            try {
                w.awaitProducer(5_000L);
                fail("expected awaitProducer to rethrow the producer's IOException");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private static Map<String, byte[]> drain(InputStream in) throws IOException {
        final Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[4096];
                int len;
                while ((len = zis.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                entries.put(e.getName(), out.toByteArray());
                zis.closeEntry();
            }
        }
        return entries;
    }

    private static void assertArrayContains(Map<String, byte[]> entries, String name, String expected) {
        final byte[] bytes = entries.get(name);
        assertNotNull("expected entry " + name + " in zip; got " + entries.keySet(), bytes);
        assertEquals(expected, new String(bytes, StandardCharsets.UTF_8));
    }
}
