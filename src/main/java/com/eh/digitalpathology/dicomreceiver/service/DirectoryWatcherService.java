package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.WatcherProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Continuously polls a single configured folder for new DICOM files. This is the only
 * supported ingestion method: a file is only handed off for processing once it has been
 * stable (same size and modification time) across two consecutive polls, which avoids
 * picking up a file that's still being written/copied into the folder.
 */
@Service
public class DirectoryWatcherService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryWatcherService.class);

    private final Path watchDir;
    private final FileProcessingService fileProcessingService;
    private final Map<Path, FileSnapshot> lastSeen = new ConcurrentHashMap<>();

    public DirectoryWatcherService(WatcherProperties watcherProperties,
                                    FileProcessingService fileProcessingService) throws IOException {
        this.watchDir = Paths.get(watcherProperties.getDirectory());
        this.fileProcessingService = fileProcessingService;
        Files.createDirectories(watchDir);
    }

    @Scheduled(fixedDelayString = "${app.watch.poll-interval-ms}")
    public void poll() {
        try (Stream<Path> files = Files.list(watchDir)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isCandidate)
                    .forEach(this::checkStabilityAndProcess);
        } catch (IOException e) {
            log.error("Failed to poll watch directory {}: {}", watchDir, e.getMessage());
        }
    }

    private boolean isCandidate(Path file) {
        String name = file.getFileName().toString();
        return !name.startsWith(".")
                && !name.endsWith(".tmp")
                && !name.endsWith(".part");
    }

    private void checkStabilityAndProcess(Path file) {
        try {
            FileSnapshot current = new FileSnapshot(Files.size(file), Files.getLastModifiedTime(file).toMillis());
            FileSnapshot previous = lastSeen.put(file, current);

            if (current.equals(previous)) {
                lastSeen.remove(file);
                fileProcessingService.processFile(file);
            }
        } catch (IOException e) {
            log.warn("Could not stat {}: {}", file, e.getMessage());
        }
    }

    private record FileSnapshot(long size, long lastModifiedMillis) {
    }
}
