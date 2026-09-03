package com.eh.digitalpathology.dicomreceiver.service.smb;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.SharedFolderConfig;
import com.eh.digitalpathology.dicomreceiver.service.DicomExtractorService;
import com.eh.digitalpathology.dicomreceiver.service.MetadataDispatcher;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class RemoteDirectoryServiceTest {


    @Mock
    private ExecutorService executorService;

    @Mock
    private SharedFolderConfig sharedFolderConfig;

    @Mock
    private MetadataDispatcher metadataDispatcher;

    @Mock
    private DicomObjectSink dicomObjectSink;

    @Mock
    private DicomExtractorService dicomExtractorService;

    @Mock
    private FileObject fileObject;

    @Mock
    private FileName fileName;

    @Mock
    private FileContent fileContent;

    @InjectMocks
    @Spy
    private RemoteDirectoryService service;

    @BeforeEach
    void setup ( ) throws Exception {

        // ----- Apache VFS wiring -----
        when( fileObject.getName( ) ).thenReturn( fileName );
        when( fileName.getPath( ) ).thenReturn( "smb://server/share/test.dcm" );
        lenient( ).when( fileName.getBaseName( ) ).thenReturn( "test.dcm" );
        when( fileObject.getContent( ) ).thenReturn( fileContent );

        // default size = 50 MB
        when( fileContent.getSize( ) ).thenReturn( 50L * 1024 * 1024 );
        lenient().doReturn(true)
                .when(service)
                .establishConnectionAndCopy(
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyBoolean());
        // ----- Run executor synchronously -----
        lenient( ).when( executorService.submit( any( Callable.class ) ) )
                .thenAnswer( invocation -> {

                    Callable< Boolean > callable =
                            invocation.getArgument( 0 );

                    FutureTask< Boolean > future =
                            new FutureTask<>( callable );

                    future.run( );

                    return future;
                } );


        lenient( ).doAnswer( invocation -> {

            Runnable runnable = invocation.getArgument( 0 );
            runnable.run( );

            return null;

        } ).when( executorService ).execute( any( Runnable.class ) );
    }

    // ------------------------------------------------------------------
    // 1) Happy path – small file
    // ------------------------------------------------------------------
    @Test
    void processFileEvent_smallFile_shouldCompleteSuccessfully ( ) throws Exception {

        CompletableFuture< Boolean > future = service.processFileEvent( fileObject, "smb://server/share/" );
        assertNotNull( future );
        assertTrue( future.get( ) );
    }

    // ------------------------------------------------------------------
    // 2) Large file (> 1 GB) – semaphore gated path
    // ------------------------------------------------------------------
    @Test
    void processFileEvent_largeFile_shouldCompleteSuccessfully ( ) throws Exception {
        when( fileContent.getSize( ) ).thenReturn( 2L * 1024 * 1024 * 1024 ); // 2 GB
        CompletableFuture< Boolean > future = service.processFileEvent( fileObject, "smb://server/share/" );
        assertTrue( future.get( ) );
    }

    // ------------------------------------------------------------------
    // 3) size lookup failure → fallback size
    // ------------------------------------------------------------------
    @Test
    void processFileEvent_whenSizeLookupFails_shouldFallbackAndComplete ( ) throws Exception {
        when( fileContent.getSize( ) ).thenThrow( new RuntimeException( "size error" ) );
        CompletableFuture< Boolean > future = service.processFileEvent( fileObject, "smb://server/share/" );
        assertTrue( future.get( ) );
    }

    // ------------------------------------------------------------------
    // 4) DICOMDIR → delegated directly to extractor
    // ------------------------------------------------------------------
    @Test
    void processFileEvent_dicomDir_shouldDelegateToExtractorAndComplete ( ) throws Exception {

        when( fileName.getBaseName( ) ).thenReturn( "DICOMDIR" );
        CompletableFuture< Boolean > future = service.processFileEvent( fileObject, "smb://server/share/" );
        assertTrue( future.get( ) );
    }

    // ------------------------------------------------------------------
    // 5) Executor throws → CompletableFuture completed exceptionally
    // ------------------------------------------------------------------
    @Test
    void processFileEvent_executorThrows_shouldCompleteExceptionally ( ) {

        doThrow( new RuntimeException( "executor failure" ) )
                .when( executorService )
                .execute( any( Runnable.class ) );

        CompletableFuture< Boolean > future = service.processFileEvent( fileObject, "smb://server/share/" );
        assertTrue( future.isCompletedExceptionally( ) );
    }

}