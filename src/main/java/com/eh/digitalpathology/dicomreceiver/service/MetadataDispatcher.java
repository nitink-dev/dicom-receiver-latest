package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

@Service
public class MetadataDispatcher {

    private static final Logger log = LoggerFactory.getLogger( MetadataDispatcher.class.getName( ) );

    private final ExecutorService executorService;
    private final FileProcessingService fileProcessingService;

    public MetadataDispatcher ( @Qualifier( "directoryWatcherExecutor" ) ExecutorService executorService, FileProcessingService fileProcessingService ) {
        this.executorService = executorService;
        this.fileProcessingService = fileProcessingService;
    }

    public void submit ( StorageObjectRef ref ) {
        executorService.submit( ( ) -> {
            try {
                fileProcessingService.processFile( ref );
            } catch ( Exception e ) {
                log.error( "Processing failed for {}", ref, e );
            }
        } );
    }

}
