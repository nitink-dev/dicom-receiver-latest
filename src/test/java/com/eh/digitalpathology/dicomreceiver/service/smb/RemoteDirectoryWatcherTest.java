package com.eh.digitalpathology.dicomreceiver.service.smb;

import com.eh.digitalpathology.dicomreceiver.config.SharedFolderConfig;
import com.eh.digitalpathology.dicomreceiver.enums.DicomKind;
import com.eh.digitalpathology.dicomreceiver.service.DicomClassifier;
import com.eh.digitalpathology.dicomreceiver.service.RedisClient;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import net.idauto.oss.jcifsng.vfs2.provider.SmbFileSystemConfigBuilder;
import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class RemoteDirectoryWatcherTest {

    @Mock
    RemoteDirectoryService remoteDirectoryService;
    @Mock
    RedisClient redisClient;
    @Mock
    SharedFolderConfig sharedFolderConfig;
    @Mock
    ScheduledExecutorService scheduler;

    ExecutorService executorService;
    @Mock
    FileSystemManager fsManager;

    @Mock
    private DicomClassifier dicomClassifier;

    @Mock
    private ScheduledFuture scheduledFuture;

    @Mock
    FileObject rootDir;

    private RemoteDirectoryWatcher watcher;

    @BeforeEach
    void setup ( ) {
        ExecutorService sameThreadExecutor = new AbstractExecutorService( ) {

            @Override
            public void execute ( Runnable command ) {
                command.run( ); // ✅ synchronous
            }

            @Override
            public void shutdown ( ) {
            }

            @Override
            public List< Runnable > shutdownNow ( ) {
                return List.of( );
            }

            @Override
            public boolean isShutdown ( ) {
                return false;
            }

            @Override
            public boolean isTerminated ( ) {
                return false;
            }

            @Override
            public boolean awaitTermination ( long timeout, TimeUnit unit ) {
                return true;
            }
        };
        this.executorService = sameThreadExecutor;
        when( scheduler.scheduleAtFixedRate( any( Runnable.class ), anyLong( ), anyLong( ), any( TimeUnit.class ) ) ).thenReturn( scheduledFuture );
        when( scheduledFuture.cancel( false ) ).thenReturn( true );
        watcher = new RemoteDirectoryWatcher( sharedFolderConfig, remoteDirectoryService, dicomClassifier, redisClient, executorService, scheduler );
        ReflectionTestUtils.setField( watcher, "fsManager", fsManager );
        ReflectionTestUtils.setField( watcher, "smbUrl", "/server/share" );
    }

    // -------------------------------------------------
    // scanDirectoryRecursively – NEW FILE SUCCESS
    // -------------------------------------------------

    @Test
    void scanDirectory_newFileProcessed_forWsiDicom ( ) throws Exception {

        FileObject file = mock( FileObject.class );
        FileName name = mock( FileName.class );
        FileContent content = mock( FileContent.class );

        when( rootDir.getChildren( ) ).thenReturn( new FileObject[]{ file } );

        doReturn( FileType.FILE ).when( file ).getType( );

        when( file.getName( ) ).thenReturn( name );
        when( name.getBaseName( ) ).thenReturn( "sample.dcm" );
        when( name.getURI( ) ).thenReturn( "/files/sample.dcm" );

        when( file.getContent( ) ).thenReturn( content );
        when( content.getSize( ) ).thenReturn( 1024L );
        when( content.getLastModifiedTime( ) ).thenReturn( 1L, 1L );

        doNothing( ).when( file ).refresh( );

        // ✅ FIX: allow loop to exit
        when( redisClient.isFileProcessed( anyString( ) ) ).thenReturn( false )  // first pass → process
                .thenReturn( true )   // next → processed
                .thenReturn( true ).thenReturn( true );

        when( redisClient.tryLockFile( anyString( ), any( ) ) ).thenReturn( true );

        when( dicomClassifier.classify( any( ) ) ).thenReturn( DicomKind.WSI );

        when( remoteDirectoryService.processFileEvent( any( ), any( ) ) ).thenReturn( CompletableFuture.completedFuture( true ) );

        // ✅ spy to override sleep
        RemoteDirectoryWatcher watcherSpy = spy( watcher );
        doNothing( ).when( watcherSpy ).sleep( anyLong( ) );

        ReflectionTestUtils.invokeMethod( watcherSpy, "processFolder", rootDir );

        verify( remoteDirectoryService ).processFileEvent( any( FileObject.class ), anyString( ) );

        verify( redisClient ).markFileAsProcessed( "/files/sample.dcm" );
        verify( redisClient ).releaseFileLock( "/files/sample.dcm" );
    }


    // -------------------------------------------------
    // scanDirectoryRecursively – HIDDEN FILE SKIPPED
    // -------------------------------------------------
    @Test
    void scanDirectory_hiddenFileSkipped ( ) throws Exception {

        FileObject hidden = mock( FileObject.class );
        FileName name = mock( FileName.class );

        when( rootDir.getChildren( ) ).thenReturn( new FileObject[]{ hidden } );
        when( hidden.getName( ) ).thenReturn( name );
        when( hidden.getType( ) ).thenReturn( FileType.FILE ); // ✅ MUST ADD

        when( name.getBaseName( ) ).thenReturn( ".secret" );
        when( name.getURI( ) ).thenReturn( "/hidden/.secret" );

        ReflectionTestUtils.invokeMethod( watcher, "processFolderForQueue", // ✅ updated method
                rootDir );
        verify( remoteDirectoryService, never( ) ).processFileEvent( any( ), any( ) );
        verify( redisClient, never( ) ).tryLockFile( any( ), any( ) );
    }

    // -------------------------------------------------
    // scanDirectoryRecursively – SUBFOLDER SCANNED
    // -------------------------------------------------
    @Test
    void processFolderForQueue_subFolderRecursed_noLeafQueued ( ) throws Exception {

        FileObject folder = mock( FileObject.class );
        FileObject innerFolder = mock( FileObject.class );

        FileName name = mock( FileName.class );
        FileName innerName = mock( FileName.class );

        when( rootDir.getChildren( ) ).thenReturn( new FileObject[]{ folder } );

        doReturn( FileType.FOLDER ).when( folder ).getType( );
        doReturn( FileType.FOLDER ).when( innerFolder ).getType( );

        when( folder.getChildren( ) ).thenReturn( new FileObject[]{ innerFolder } );
        when( innerFolder.getChildren( ) ).thenReturn( new FileObject[ 0 ] );

        when( folder.getName( ) ).thenReturn( name );
        when( name.getURI( ) ).thenReturn( "/folder" );

        when( innerFolder.getName( ) ).thenReturn( innerName );
        when( innerName.getURI( ) ).thenReturn( "/folder/inner" );

        ReflectionTestUtils.invokeMethod( watcher, "processFolderForQueue", rootDir );

        // ✅ Correct behavior assertions
        verify( redisClient, never( ) ).enqueueFolder( "/folder" );
        verify( redisClient, never( ) ).enqueueFolder( "/folder/inner" );
    }

    @Test
    void processFolderForQueue_leafFolderQueued ( ) throws Exception {

        FileObject folder = mock( FileObject.class );
        FileObject file = mock( FileObject.class );

        FileName folderName = mock( FileName.class );
        FileName fileName = mock( FileName.class );
        FileName fallbackName = mock( FileName.class );

        RemoteDirectoryWatcher watcherSpy = spy( watcher );

        // ✅ root
        when( rootDir.getChildren( ) ).thenReturn( new FileObject[]{ folder } );

        // ✅ fallback protection (CRITICAL FIX)
        doReturn( fallbackName ).when( rootDir ).getName( );
        when( fallbackName.getURI( ) ).thenReturn( "fallback" );
        when( fallbackName.getBaseName( ) ).thenReturn( "fallback" );

        // ✅ FORCE types
        doReturn( FileType.FOLDER ).when( watcherSpy ).safeGetType( folder );
        doReturn( FileType.FILE ).when( watcherSpy ).safeGetType( file );

        // ✅ folder
        doReturn( folderName ).when( folder ).getName( );
        when( folderName.getURI( ) ).thenReturn( "/folder" );
        when( folderName.getBaseName( ) ).thenReturn( "folder" );

        when( folder.getChildren( ) ).thenReturn( new FileObject[]{ file } );

        // ✅ file
        doReturn( fileName ).when( file ).getName( );
        when( fileName.getBaseName( ) ).thenReturn( "file.dcm" );
        when( fileName.getURI( ) ).thenReturn( "/folder/file.dcm" );

        // ✅ prevent recursion issues
        when( file.getChildren( ) ).thenReturn( new FileObject[ 0 ] );
        when( redisClient.enqueueFolder( "/folder" ) ).thenReturn( true );
        // ✅ execute
        ReflectionTestUtils.invokeMethod( watcherSpy, "processFolderForQueue", rootDir );

        // ✅ verify
        verify( redisClient ).enqueueFolder( "/folder" );
    }


    // -------------------------------------------------
    // resolveSharedFolder – SCHEDULER INVOKED
    // -------------------------------------------------
    @Test
    void resolveSharedFolder_schedulesScan ( ) {

        ReflectionTestUtils.setField( watcher, "fsOptions", new FileSystemOptions( ) );
        ArgumentCaptor< Runnable > captor = ArgumentCaptor.forClass( Runnable.class );
        lenient( ).when( scheduler.scheduleWithFixedDelay( captor.capture( ), eq( 0L ), eq( 5L ), eq( TimeUnit.SECONDS ) ) ).thenReturn( mock( ScheduledFuture.class ) );
        ReflectionTestUtils.invokeMethod( watcher, "resolveSharedFolder" );
        assertNotNull( captor.getValue( ) );
    }

    // -------------------------------------------------
    // watchSharedDirectory – HAPPY PATH
    // -------------------------------------------------
    @Test
    void watchSharedDirectory_success ( ) throws FileSystemException {

        when( sharedFolderConfig.getUsername( ) ).thenReturn( "user" );
        when( sharedFolderConfig.getPassword( ) ).thenReturn( "pass" );
        when( sharedFolderConfig.getServername( ) ).thenReturn( "server" );
        when( sharedFolderConfig.getSharepath( ) ).thenReturn( "/share" );

        doAnswer( invocation -> {
            Runnable task = invocation.getArgument( 0 );
            task.run( );
            return null;
        } ).when( scheduler ).scheduleWithFixedDelay( any( Runnable.class ), anyLong( ), anyLong( ), any( TimeUnit.class ) );

        FileObject root = mock( FileObject.class );
        when( fsManager.resolveFile( anyString( ), any( ) ) ).thenReturn( root );
        when( root.getChildren( ) ).thenReturn( new FileObject[ 0 ] );
        doNothing( ).when( root ).refresh( );

        try ( MockedStatic< VFS > vfs = mockStatic( VFS.class ); MockedStatic< SmbFileSystemConfigBuilder > smb = mockStatic( SmbFileSystemConfigBuilder.class ); MockedStatic< DefaultFileSystemConfigBuilder > dfs = mockStatic( DefaultFileSystemConfigBuilder.class );
              // ✅ THIS IS THE CRITICAL FIX
              MockedConstruction< PropertyConfiguration > propMock = mockConstruction( PropertyConfiguration.class );
              MockedConstruction< BaseContext > baseContextMock = mockConstruction( BaseContext.class );
              MockedConstruction< URIBuilder > uri = mockConstruction( URIBuilder.class, ( b, c ) -> {

                  when( b.setScheme( any( ) ) ).thenReturn( b );
                  when( b.setHost( any( ) ) ).thenReturn( b );
                  when( b.setPath( any( ) ) ).thenReturn( b );
                  when( b.toString( ) ).thenReturn( "smb://server/share" );
              } )

        ) {

            vfs.when( VFS::getManager ).thenReturn( fsManager );

            SmbFileSystemConfigBuilder smbBuilder = mock( SmbFileSystemConfigBuilder.class );
            smb.when( SmbFileSystemConfigBuilder::getInstance ).thenReturn( smbBuilder );
            doNothing( ).when( smbBuilder ).setCIFSContext( any( ), any( ) );

            DefaultFileSystemConfigBuilder dfsBuilder = mock( DefaultFileSystemConfigBuilder.class );
            dfs.when( DefaultFileSystemConfigBuilder::getInstance ).thenReturn( dfsBuilder );
            doNothing( ).when( dfsBuilder ).setUserAuthenticator( any( ), any( ) );

            watcher.watchSharedDirectory( );

            // ✅ NOW THIS WILL PASS
            assertTrue( uri.constructed( ).size( ) > 0 );

            URIBuilder builder = uri.constructed( ).get( 0 );
            verify( builder ).setHost( "server" );
            verify( builder ).setPath( "/share" );
        }
    }
}