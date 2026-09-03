package com.eh.digitalpathology.dicomreceiver.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger( ExecutorConfig.class );

    private ExecutorService directoryWatcherExecutor;
    private ExecutorService remoteDirectoryWatcherExecutor;
    private ExecutorService remoteDirectoryProcessingExecutor;
    private ScheduledExecutorService remoteDirectoryWatcherScheduledExecutor;
    private ScheduledExecutorService barcodeUploadScheduledExecutor;
    private ScheduledExecutorService deleteSchedular;

    @Bean( name = "directoryWatcherExecutor" )
    public ExecutorService directoryWatcherExecutor ( ) {
        this.directoryWatcherExecutor = Executors.newScheduledThreadPool( Runtime.getRuntime( ).availableProcessors( ) );
        return this.directoryWatcherExecutor;
    }

    @Bean( name = "remoteDirectoryWatcherExecutor" )
    public ExecutorService remoteDirectoryWatcherExecutor ( ) {
        this.remoteDirectoryWatcherExecutor = new ThreadPoolExecutor( 32, 64, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>( 10000 ), new ThreadPoolExecutor.CallerRunsPolicy() );
        return this.remoteDirectoryWatcherExecutor;
    }

    @Bean( name = "remoteDirectoryProcessingExecutor" )
    public ExecutorService remoteDirectoryProcessingExecutor ( ) {
        this.remoteDirectoryProcessingExecutor = new ThreadPoolExecutor( 32, 32, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>( 10000 ), new ThreadPoolExecutor.CallerRunsPolicy( ) );
        return this.remoteDirectoryProcessingExecutor;
    }

    @Bean( name = "remoteDirectoryWatcherScheduledExecutor" )
    public ScheduledExecutorService remoteDirectoryWatcherScheduledExecutor ( ) {
        this.remoteDirectoryWatcherScheduledExecutor = Executors.newScheduledThreadPool( Runtime.getRuntime( ).availableProcessors( ) );
        return this.remoteDirectoryWatcherScheduledExecutor;
    }

    @Bean( name = "barcodeUploadScheduledExecutor" )
    public ScheduledExecutorService barcodeUploadScheduledExecutor ( ) {
        this.barcodeUploadScheduledExecutor = Executors.newScheduledThreadPool( Runtime.getRuntime( ).availableProcessors( ) );
        return this.barcodeUploadScheduledExecutor;
    }

    @Bean( name = "deleteSchedular" )
    public ScheduledExecutorService deleteSchedular ( ) {
        this.deleteSchedular = Executors.newScheduledThreadPool( 2 );
        return this.deleteSchedular;
    }

    @PreDestroy
    public void shutdownExecutors ( ) {

        shutdownExecutor( directoryWatcherExecutor, "Directory Watcher ExecutorService" );
        shutdownExecutor( remoteDirectoryWatcherExecutor, " Remote Directory Watcher ExecutorService" );
        shutdownExecutor( remoteDirectoryProcessingExecutor, " Remote Directory Process ExecutorService" );
        shutdownExecutor( remoteDirectoryWatcherScheduledExecutor, " Remote Directory Watcher Scheduled ExecutorService" );
        shutdownExecutor( barcodeUploadScheduledExecutor, " Barcode Uploader Scheduled ExecutorService" );
        shutdownExecutor( deleteSchedular, " Delete Schedular ExecutorService" );
    }

    private void shutdownExecutor ( ExecutorService executor, String name ) {
        if ( executor != null ) {
            executor.shutdown( );
            try {
                if ( !executor.awaitTermination( 60, TimeUnit.SECONDS ) ) {
                    executor.shutdownNow( );
                    if ( !executor.awaitTermination( 60, TimeUnit.SECONDS ) ) {
                        log.error( "{} did not terminate", name );
                    }
                }
            } catch ( InterruptedException ex ) {
                executor.shutdownNow( );
                Thread.currentThread( ).interrupt( );
            }
        }
    }
}
