/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.logmanager.handlers;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class AbstractHandlerTest {
    static final File BASE_LOG_DIR;

    static {
        BASE_LOG_DIR = new File(System.getProperty("log.dir"));
    }

    final static PatternFormatter FORMATTER = new PatternFormatter("%d{HH:mm:ss,SSS} %-5p [%c] (%t) %s%E%n");

    @Before
    public void setup() throws Exception {
        BASE_LOG_DIR.mkdir();
    }

    @After
    public void cleanUp() throws Exception {
        deleteChildrenRecursively(BASE_LOG_DIR);
    }

    static boolean deleteRecursively(final File dir) {
        if (dir.isDirectory()) {
            final File[] files = dir.listFiles();
            if (files != null) {
                for (final File f : files) {
                    if (f.isDirectory()) {
                        if (!deleteRecursively(f)) {
                            return false;
                        }
                    }
                    if (!f.delete()) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    static boolean deleteChildrenRecursively(final File dir) {
        if (dir.isDirectory()) {
            final File[] files = dir.listFiles();
            if (files != null) {
                for (final File f : files) {
                    if (f.isDirectory()) {
                        if (!deleteRecursively(f)) {
                            return false;
                        }
                    }
                    if (!f.delete()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    protected static void configureHandlerDefaults(final ExtHandler handler) {
        handler.setAutoFlush(true);
        handler.setFormatter(FORMATTER);
        handler.setErrorManager(AssertingErrorManager.of());
    }

    protected ExtLogRecord createLogRecord(final String msg) {
        return createLogRecord(org.jboss.logmanager.Level.INFO, msg);
    }

    protected ExtLogRecord createLogRecord(final String format, final Object... args) {
        return createLogRecord(org.jboss.logmanager.Level.INFO, format, args);
    }

    protected ExtLogRecord createLogRecord(final org.jboss.logmanager.Level level, final String msg) {
        return new ExtLogRecord(level, msg, getClass().getName());
    }

    protected ExtLogRecord createLogRecord(final org.jboss.logmanager.Level level, final String format, final Object... args) {
        return new ExtLogRecord(level, String.format(format, args), getClass().getName());
    }

    /**
     * Validates that at least one line of the GZIP'd file contains the expected text.
     *
     * @param path             the path to the GZIP file
     * @param expectedContains the expected text
     *
     * @throws IOException if an error occurs while reading the GZIP file
     */
    static void validateGzipContents(final Path path, final String expectedContains) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(expectedContains)) {
                    return;
                }
            }
        }
        Assert.fail(String.format("GZIP file %s missing contents: %s", path, expectedContains));
    }

    /**
     * Validates that the ZIP file contains the expected file, the expected file is not empty and that the first line
     * contains the expected text.
     *
     * @param path             the path to the zip file
     * @param expectedFileName the name of the file inside the zip file
     * @param expectedContains the expected text
     *
     * @throws IOException if an error occurs reading the zip file
     */
    static void validateZipContents(final Path path, final String expectedFileName, final String expectedContains) throws IOException {
        try (final FileSystem zipFs = FileSystems.newFileSystem(URI.create("jar:" +  path.toUri().toASCIIString()), Collections.singletonMap("create", "true"))) {
            final Path file = zipFs.getPath(zipFs.getSeparator(), expectedFileName);
            Assert.assertTrue(String.format("Expected file %s not found.", expectedFileName), Files.exists(file));
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Assert.assertFalse(String.format("File %s appears to be empty in zip file %s.", expectedFileName, path), lines.isEmpty());
            Assert.assertTrue(String.format("ZIP file %s missing contents: %s", path, expectedContains), lines.get(0).contains(expectedContains));
        }
    }

    static void compareArchiveContents(final Path archive1, final Path archive2, final String expectedFileName) throws IOException {
        Collection<String> lines1 = Collections.emptyList();
        Collection<String> lines2 = Collections.emptyList();

        if (archive1.getFileName().toString().endsWith(".zip")) {
            lines1 = readAllLinesFromZip(archive1, expectedFileName);
            lines2 = readAllLinesFromZip(archive2, expectedFileName);
        } else if (archive1.getFileName().toString().endsWith(".gz")) {
            lines1 = readAllLinesFromGzip(archive1);
            lines2 = readAllLinesFromGzip(archive2);
        } else {
            Assert.fail(String.format("Files %s and %s are not archives.", archive1, archive2));
        }

        // Assert the contents aren't empty
        Assert.assertFalse(String.format("Archive %s contained no data", archive1), lines1.isEmpty());
        Assert.assertFalse(String.format("Archive %s contained no data", archive2), lines2.isEmpty());

        final Collection<String> copy1 = new ArrayList<>(lines1);
        final Collection<String> copy2 = new ArrayList<>(lines2);
        boolean altered = copy1.removeAll(copy2);
        if (copy1.size() == 0) {
            Assert.fail(String.format("The contents of %s and %s are identical and should not be", archive1, archive2));
        } else if (altered) {
            final StringBuilder msg = new StringBuilder(1024)
                    .append("The following contents are in both ")
                    .append(archive1)
                    .append(" and ")
                    .append(archive2);
            // Find the identical lines and report
            for (String line : lines1) {
                if (lines2.contains(line)) {
                    msg.append(System.lineSeparator()).append(line);
                }
            }
            Assert.fail(msg.toString());
        }
    }

    private static Collection<String> readAllLinesFromZip(final Path path, final String expectedFileName) throws IOException {
        try (final FileSystem zipFs = FileSystems.newFileSystem(URI.create("jar:" + path.toUri().toASCIIString()), Collections.singletonMap("create", "true"))) {
            final Path file = zipFs.getPath(zipFs.getSeparator(), expectedFileName);
            Assert.assertTrue(String.format("Expected file %s not found.", expectedFileName), Files.exists(file));
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        }
    }

    private static Collection<String> readAllLinesFromGzip(final Path path) throws IOException {
        final Collection<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
