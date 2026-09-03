package com.eh.digitalpathology.dicomreceiver.service.smb;

import com.eh.digitalpathology.dicomreceiver.config.SharedFolderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RemoteDirectoryWatcherLifeCycle implements SmartLifecycle {


    private static final Logger log = LoggerFactory.getLogger( RemoteDirectoryWatcherLifeCycle.class.getName( ) );

    private final RemoteDirectoryWatcher watcher;
    private final SharedFolderConfig sharedFolderConfig;

    private volatile ExecutorService executor;
    private volatile boolean running = false;

    public RemoteDirectoryWatcherLifeCycle ( RemoteDirectoryWatcher watcher, SharedFolderConfig sharedFolderConfig ) {
        this.watcher = watcher;
        this.sharedFolderConfig = sharedFolderConfig;
    }

    @Override
    public synchronized void start() {
        if (!sharedFolderConfig.isEnableRemoteAccess()) {
            log.info("Remote directory watcher is disabled by configuration");
            return;
        }

        if (running) {
            log.debug("Remote directory watcher already running");
            return;
        }

        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "remote-directory-watcher");
                t.setDaemon(false);
                return t;
            });
        }

        log.info("Starting remote directory watcher");

        executor.submit(() -> {
            try {
                watcher.watchSharedDirectory();
            } catch (Exception e) {
                log.error("Remote directory watcher failed", e);
            }
        });

        running = true;
    }

    @Override
    public synchronized void stop() {
        log.info("Stopping remote directory watcher");
        running = false;
        watcher.stopWatching();

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

}
