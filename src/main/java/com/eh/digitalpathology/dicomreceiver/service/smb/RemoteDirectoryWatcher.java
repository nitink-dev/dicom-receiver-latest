package com.eh.digitalpathology.dicomreceiver.service.smb;

import com.eh.digitalpathology.dicomreceiver.config.SharedFolderConfig;
import com.eh.digitalpathology.dicomreceiver.constants.WatchDirectoryConstant;
import com.eh.digitalpathology.dicomreceiver.enums.DicomKind;
import com.eh.digitalpathology.dicomreceiver.service.DicomClassifier;
import com.eh.digitalpathology.dicomreceiver.service.RedisClient;
import jakarta.annotation.PreDestroy;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import net.idauto.oss.jcifsng.vfs2.provider.SmbFileSystemConfigBuilder;
import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.auth.StaticUserAuthenticator;
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RemoteDirectoryWatcher {

    private static final Logger logger = LoggerFactory.getLogger( RemoteDirectoryWatcher.class );

    private static final String SMB_CLIENT_MAX_VERSION = "SMB311";
    private static final String SMB_CLIENT_MIN_VERSION = "SMB202";

    private static final int FOLDER_CONCURRENCY = 4;
    private static final int FOLDER_IDLE_CONFIRMATION_CYCLES = 3;
    private static final long FOLDER_IDLE_SLEEP_MS = 500L;
    private static final long EMPTY_QUEUE_SLEEP_MS = 250L;
    private static final long SCAN_INTERVAL_SECONDS = 5L;
    private static final int MAX_STABILITY_RETRIES = 5;

    private final SharedFolderConfig sharedFolderConfig;
    private final RemoteDirectoryService remoteDirectoryService;
    private final DicomClassifier dicomClassifier;
    private final RedisClient redisClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService processingExecutor;
    private final Semaphore dicomReadLimit = new Semaphore( 8, true );
    private volatile ScheduledFuture<?> folderScanTask;
    private volatile ScheduledFuture<?> processedCountTask;

    private volatile ExecutorService folderExecutor ;

    private final AtomicBoolean started = new AtomicBoolean( false );
    private final AtomicBoolean running = new AtomicBoolean( true );

    private FileSystemManager fsManager;
    private FileSystemOptions fsOptions;
    private String smbUrl;

    private final AtomicInteger processedFileCount = new AtomicInteger( 0 );
    private volatile LocalDate lastProcessedDate = LocalDate.now( );

    public RemoteDirectoryWatcher ( SharedFolderConfig sharedFolderConfig, RemoteDirectoryService remoteDirectoryService, DicomClassifier dicomClassifier, RedisClient redisClient, @Qualifier( "remoteDirectoryProcessingExecutor" ) ExecutorService processingExecutor, @Qualifier(
            "remoteDirectoryWatcherScheduledExecutor" ) ScheduledExecutorService scheduler ) {

        this.sharedFolderConfig = sharedFolderConfig;
        this.remoteDirectoryService = remoteDirectoryService;
        this.dicomClassifier = dicomClassifier;
        this.redisClient = redisClient;
        this.processingExecutor = processingExecutor;
        this.scheduler = scheduler;
    }

    public void watchSharedDirectory ( ) {
        running.set(true);
        if ( !started.compareAndSet( false, true ) ) {
            logger.warn( "watchSharedDirectory :: Watcher already started. Ignoring duplicate start." );
            return;
        }
        folderExecutor = Executors.newFixedThreadPool( FOLDER_CONCURRENCY );

        try {
            fsManager = VFS.getManager( );
            fsOptions = new FileSystemOptions( );
            Properties props = new Properties( );
            props.setProperty( WatchDirectoryConstant.SMB_MIN_VERSION, SMB_CLIENT_MIN_VERSION );
            props.setProperty( WatchDirectoryConstant.SMB_MAX_VERSION, SMB_CLIENT_MAX_VERSION );
            CIFSContext context = new BaseContext( new PropertyConfiguration( props ) );
            if ( StringUtils.hasText( sharedFolderConfig.getUsername( ) ) && StringUtils.hasText( sharedFolderConfig.getPassword( ) ) ) {
                StaticUserAuthenticator auth = new StaticUserAuthenticator( "", sharedFolderConfig.getUsername( ), sharedFolderConfig.getPassword( ) );
                DefaultFileSystemConfigBuilder.getInstance( ).setUserAuthenticator( fsOptions, auth );
            }
            SmbFileSystemConfigBuilder.getInstance( ).setCIFSContext( fsOptions, context );
            smbUrl = new URIBuilder( ).setScheme( "smb" ).setHost( sharedFolderConfig.getServername( ) ).setPath( sharedFolderConfig.getSharepath( ) ).toString( );
            logger.info( "watchSharedDirectory :: Watching {}", smbUrl );
            startFolderWorkers( );
            startProcessedCountLogger( );
            resolveSharedFolder( );

        } catch ( Exception e ) {
            started.set( false );
            logger.error( "watchSharedDirectory :: Init error :: {}", e.getMessage( ) );
        }
    }

    private void startFolderWorkers ( ) {
        for ( int i = 0; i < FOLDER_CONCURRENCY; i++ ) {
            folderExecutor.submit( this::folderWorker );
        }
    }

    private void resolveSharedFolder ( ) {
        folderScanTask = scheduler.scheduleWithFixedDelay( ( ) -> {
            if ( !running.get( ) ) {
                return;
            }

            try ( FileObject root = fsManager.resolveFile( smbUrl, fsOptions ) ) {
                root.refresh( );
                scanFolders( root );
            } catch ( Exception e ) {
                logger.error( "resolveSharedFolder :: Scan error :: {}", e.getMessage( ) );
            }

        }, 0, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS );
    }

    private void scanFolders ( FileObject root ) {
        try {
            FileObject[] children = root.getChildren( );
            if ( children == null || children.length == 0 ) {
                return;
            }

            for ( FileObject child : children ) {
                if ( safeGetType( child ) == FileType.FOLDER ) {
                    processFolderForQueue( child );
                } else if ( safeGetType( child ) == FileType.FILE && !skipByName( child ) ) {
                    /*
                     * Optional support:
                     * If direct files exist under root folder, root itself can be queued.
                     * But usually root should not be treated as one processing folder because it may
                     * contain many date/series folders.
                     */
                    logger.debug( "scanFolders :: Direct root file detected {}", safeUri( child ) );
                }
            }

        } catch ( Exception e ) {
            logger.error( "scanFolders :: Error :: {}", e.getMessage( ) );
        }
    }

    private void processFolderForQueue ( FileObject folder ) {
        try {
            folder.refresh( );

            FileObject[] children = folder.getChildren( );
            if ( children == null || children.length == 0 ) {
                return;
            }

            boolean hasFiles = false;
            boolean hasSubFolders = false;

            for ( FileObject child : children ) {
                FileType type = safeGetType( child );

                if ( type == FileType.FILE && !skipByName( child ) ) {
                    hasFiles = true;
                } else if ( type == FileType.FOLDER ) {
                    hasSubFolders = true;
                }
            }
            String folderPath = folder.getName( ).getURI( );
            /*
             * Leaf folder rule:
             * Queue only folders that contain files and do not contain child folders.
             * This prevents parent + child duplicate processing.
             */
            if ( hasFiles && !hasSubFolders ) {
                enqueueLeafFolderIfEligible( folder, folderPath, children );
                return;
            }

            /*
             * Recurse into child folders.
             */
            if ( hasSubFolders ) {
                for ( FileObject child : children ) {
                    if ( safeGetType( child ) == FileType.FOLDER ) {
                        processFolderForQueue( child );
                    }
                }
            }
        } catch ( Exception e ) {
            logger.error( "processFolderForQueue :: Error for folder {} :: {}", safeUri( folder ), e.getMessage( ) );
        }
    }

    private void enqueueLeafFolderIfEligible ( FileObject folder, String folderPath, FileObject[] children ) {
        try {
            /*
             * Critical fix #1:
             * If already queued, do not enqueue again.
             */
            if ( redisClient.isFolderQueued( folderPath ) ) {
                logger.debug( "enqueueLeafFolderIfEligible :: Already queued. Skipping {}", folderPath );
                return;
            }
            /*
             * Critical fix #2:
             * If a worker is currently processing this folder, do not enqueue again.
             */
            if ( redisClient.isFolderLocked( folderPath ) ) {
                logger.debug( "enqueueLeafFolderIfEligible :: Already processing. Skipping {}", folderPath );
                return;
            }
            boolean hasUnprocessedFiles = hasUnprocessedFiles( children );
            /*
             * Critical fix #3:
             * If completed and no new unprocessed file is present, skip.
             */
            if ( redisClient.isFolderCompleted( folderPath ) && !hasUnprocessedFiles ) {
                logger.debug( "enqueueLeafFolderIfEligible :: Completed and no new files. Skipping {}", folderPath );
                return;
            }
            /*
             * If completed but new unprocessed files arrived later, allow requeue.
             */
            if ( hasUnprocessedFiles ) {
                redisClient.clearFolderCompleted( folderPath );
            } else {
                logger.debug( "enqueueLeafFolderIfEligible :: No unprocessed files. Skipping {}", folderPath );
                return;
            }
            if ( redisClient.enqueueFolder( folderPath ) ) {
                logger.info( "processFolderForQueue :: Enqueued {}", folderPath );
            } else {
                logger.debug( "processFolderForQueue :: Not enqueued, already queued/locked {}", folderPath );
            }

        } catch ( Exception e ) {
            logger.error( "enqueueLeafFolderIfEligible :: Error for folder {} :: {}", safeUri( folder ), e.getMessage( ) );
        }
    }

    private boolean hasUnprocessedFiles ( FileObject[] children ) {
        if ( children == null || children.length == 0 ) {
            return false;
        }

        for ( FileObject child : children ) {
            try {
                if ( safeGetType( child ) != FileType.FILE ) {
                    continue;
                }

                if ( skipByName( child ) ) {
                    continue;
                }

                String fileKey = child.getName( ).getURI( );
                if ( !redisClient.isFileProcessed( fileKey ) ) {
                    return true;
                }

            } catch ( Exception e ) {
                logger.warn( "hasUnprocessedFiles :: Unable to inspect file {} :: {}", safeUri( child ), e.getMessage( ) );
            }
        }
        return false;
    }

    private ScheduledFuture< ? > startFolderLockRenewal ( String folderPath ) {
        return scheduler.scheduleAtFixedRate( ( ) -> redisClient.extendFolderLock( folderPath, Duration.ofMinutes( 30 ) ), 30, 30, TimeUnit.MINUTES );
    }

    private void folderWorker ( ) {
        while ( running.get( ) && !Thread.currentThread( ).isInterrupted( ) ) {
            String folderPath = null;
            FileObject folder = null;
            ScheduledFuture< ? > renewal = null;
            try {
                folderPath = redisClient.dequeueFolder( );

                if ( folderPath == null ) {
                    sleep( EMPTY_QUEUE_SLEEP_MS );
                    continue;
                }
                renewal = startFolderLockRenewal( folderPath );

                folder = fsManager.resolveFile( folderPath, fsOptions );
                folder.refresh( );
                logger.info( "folderWorker :: Processing {}", folderPath );
                boolean completed = processFolder( folder );
                if ( completed ) {
                    redisClient.markFolderCompleted( folderPath );
                    remoteDirectoryService.scheduleDeleteFolderIfEmpty( folderPath, smbUrl );
                    logger.info( "folderWorker :: Completed {}", folderPath );
                } else {
                    logger.warn( "folderWorker :: Folder not completed, will be re-discovered later {}", folderPath );
                }

            } catch ( Exception e ) {
                logger.error( "folderWorker :: Worker error for folder {} :: {}", folderPath, e.getMessage( ) );

            } finally {
                if ( folderPath != null ) {
                    if ( renewal != null ) {
                        renewal.cancel( false );
                    }
                    redisClient.releaseFolderLock( folderPath );
                }
                closeQuietly( folder );
            }
        }
    }

    private boolean processFolder ( FileObject folder ) {

        int idleCycles = 0;
        Map< String, CompletableFuture< Boolean > > activeFutures = new ConcurrentHashMap<>( );
        try {
            while ( running.get( ) && !Thread.currentThread( ).isInterrupted( ) ) {
                resetCounterIfNewDay( );
                folder.refresh( );
                FileObject[] files = folder.getChildren( );
                logger.info( "processFolder :: folder={} files={} activeFutures={}", safeUri( folder ), files == null ? 0 : files.length, activeFutures.size( ) );
                /*
                 * Remove completed futures.
                 */
                Iterator< Map.Entry< String, CompletableFuture< Boolean > > > iterator = activeFutures.entrySet( ).iterator( );
                while ( iterator.hasNext( ) ) {
                    Map.Entry< String, CompletableFuture< Boolean > > entry = iterator.next( );
                    CompletableFuture< Boolean > future = entry.getValue( );
                    if ( future.isDone( ) ) {
                        try {
                            Boolean result = future.getNow( false );
                            logger.info( "processFolder :: completed file={} result={}", entry.getKey( ), result );
                        } catch ( Exception e ) {
                            logger.warn( "processFolder :: future completed with error for file={}", entry.getKey( ), e );
                        }
                        iterator.remove( );
                    }
                }
                boolean foundUnprocessedFile = false;
                if ( files != null ) {
                    for ( FileObject file : files ) {
                        if ( safeGetType( file ) != FileType.FILE ) {
                            continue;
                        }
                        if ( skipByName( file ) ) {
                            continue;
                        }
                        String fileKey = file.getName( ).getURI( );
                        if ( redisClient.isFileProcessed( fileKey ) ) {
                            continue;
                        }
                        foundUnprocessedFile = true;
                        /*
                         * File already submitted and still running.
                         */
                        if ( activeFutures.containsKey( fileKey ) ) {
                            continue;
                        }
                        logger.info( "processFolder :: submitting file={}", file.getName( ).getBaseName( ) );
                        CompletableFuture< Boolean > future = handleFile( file, fileKey );
                        if ( future != null ) {
                            activeFutures.put( fileKey, future );
                            logger.info( "processFolder :: active futures={}", activeFutures.size( ) );
                        }
                    }
                }
                /*
                 * Folder is complete only when:
                 * 1. No unprocessed files exist.
                 * 2. No active futures remain.
                 */
                if ( !foundUnprocessedFile && activeFutures.isEmpty( ) ) {
                    idleCycles++;
                    logger.info( "processFolder :: idle cycle {}/{} folder={}", idleCycles, FOLDER_IDLE_CONFIRMATION_CYCLES, safeUri( folder ) );
                    if ( idleCycles >= FOLDER_IDLE_CONFIRMATION_CYCLES ) {
                        logger.info( "processFolder :: completed folder={}", safeUri( folder ) );
                        return true;
                    }
                } else {
                    idleCycles = 0;
                }
                sleep( FOLDER_IDLE_SLEEP_MS );
            }
            return false;
        } catch ( Exception e ) {
            logger.error( "processFolder :: error for folder {} :: {}", safeUri( folder ), e.getMessage( ), e );
            return false;
        }
    }

    private CompletableFuture< Boolean > handleFile ( FileObject file, String fileKey ) {
        logger.info( "handleFile :: entered {}", fileKey );
        Duration lockTtl = computeAdaptiveTtl( file );

        if ( !redisClient.tryLockFile( fileKey, lockTtl ) ) {
            logger.debug( "handleFile :: File already locked. Skipping for now {}", fileKey );
            return CompletableFuture.completedFuture( false );
        }
        ScheduledFuture< ? > renewalTask = scheduler.scheduleAtFixedRate( ( ) -> {
            try {
                redisClient.extendFileLock( fileKey, Duration.ofMinutes( 10 ) );
            } catch ( Exception e ) {
                logger.warn( "handleFile :: Unable to extend lock for {} :: {}", fileKey, e.getMessage( ) );
            }
        }, 60, 60, TimeUnit.SECONDS );
        return processFileWithRetry( file, fileKey, 0 ).whenComplete( ( ok, ex ) -> {
            renewalTask.cancel( false );
            redisClient.releaseFileLock( fileKey );
        } );
    }

    private CompletableFuture< Boolean > processFileWithRetry ( FileObject file, String fileKey, int retry ) {
        CompletableFuture< Boolean > result = new CompletableFuture<>( );
        processingExecutor.submit( ( ) -> {
            try {
                if ( !isFileStableNow( file ) ) {
                    if ( retry < MAX_STABILITY_RETRIES ) {
                        long delay = computeRetryDelay( file );
                        logger.info( "processFileWithRetry :: File not stable. Retry {}/{} after {} ms for {}", retry + 1, MAX_STABILITY_RETRIES, delay, fileKey );
                        scheduler.schedule( ( ) -> processFileWithRetry( file, fileKey, retry + 1 ).whenComplete( ( ok, ex ) -> {
                            if ( ex != null ) {
                                result.completeExceptionally( ex );
                            } else {
                                result.complete( Boolean.TRUE.equals( ok ) );
                            }
                        } ), delay, TimeUnit.MILLISECONDS );

                    } else {
                        logger.warn( "processFileWithRetry :: File not stable after max retries {}", fileKey );
                        result.complete( false );
                    }
                    return;
                }
                DicomKind kind;
                try {
                    kind = classifyWithThrottle( file );
                } catch ( TimeoutException e ) {
                    logger.debug( "processFileWithRetry :: Classification deferred for {}. Will retry later.", fileKey );
                    result.complete( false );
                    return;
                }
                /*
                 * Keeping your existing behavior:
                 * NON_DICOM and plain DICOM are marked processed and skipped.
                 * Other DicomKind values go to remoteDirectoryService.processFileEvent().
                 */
                if ( kind == DicomKind.NON_DICOM || kind == DicomKind.DICOM ) {
                    redisClient.markFileAsProcessed( fileKey );
                    resetCounterIfNewDay( );
                    logger.debug( "processFileWithRetry :: Marked skipped file as processed {} kind={}", fileKey, kind );
                    processedFileCount.incrementAndGet( );
                    result.complete( true );
                    return;
                }
                remoteDirectoryService.processFileEvent( file, smbUrl ).orTimeout( 20, TimeUnit.MINUTES ).whenComplete( ( ok, ex ) -> {
                    if ( ex != null ) {
                        logger.error( "processFileWithRetry :: Error while processing {} :: {}", fileKey, ex.getMessage( ) );
                        result.complete( false );
                        return;
                    }

                    logger.info("processFileWithRetry :: processFileEvent COMPLETE {} result={}", fileKey, ok);
                    if ( Boolean.TRUE.equals( ok ) ) {
                        redisClient.markFileAsProcessed( fileKey );
                        resetCounterIfNewDay( );
                        processedFileCount.incrementAndGet( );
                        result.complete( true );
                    } else {
                        logger.warn( "processFileWithRetry :: Processing returned false for {}", fileKey );
                        result.complete( false );
                    }
                } );

            } catch ( Exception e ) {
                logger.error( "processFileWithRetry :: Unexpected error for {} :: {}", fileKey, e.getMessage( ) );
                result.complete( false );
            }
        } );

        return result;
    }

    private boolean skipByName ( FileObject file ) {
        try {
            String name = file.getName( ).getBaseName( ).toLowerCase( );
            return name.startsWith( "." ) || name.startsWith( "~$" ) || name.endsWith( ".tmp" ) || name.endsWith( ".part" ) || name.endsWith( ".crdownload" );
        } catch ( Exception e ) {
            logger.warn( "skipByName :: Unable to read filename {}", safeUri( file ), e );
            return true;
        }
    }

    protected FileType safeGetType ( FileObject file ) {
        try {
            return file.getType( );
        } catch ( Exception e ) {
            logger.debug( "safeGetType :: Unable to read file type for {}", safeUri( file ) );
            return FileType.IMAGINARY;
        }
    }

    private boolean isFileStableNow ( FileObject file ) {
        try {
            long size1 = file.getContent( ).getSize( );
            long modified1 = file.getContent( ).getLastModifiedTime( );

            Thread.sleep( 200 );
            file.refresh( );

            long size2 = file.getContent( ).getSize( );
            long modified2 = file.getContent( ).getLastModifiedTime( );

            return size1 == size2 && modified1 == modified2;
        } catch ( Exception e ) {
            logger.debug( "isFileStableNow :: Unable to check stability for {}", safeUri( file ) );
            return false;
        }
    }

    private long computeRetryDelay ( FileObject file ) {
        try {
            long size = file.getContent( ).getSize( );
            if ( size > ( 1L << 30 ) ) {
                return 20_000L;
            }
            if ( size > ( 100L << 20 ) ) {
                return 10_000L;
            }
            return 3_000L;
        } catch ( Exception e ) {
            return 5_000L;
        }
    }

    private Duration computeAdaptiveTtl ( FileObject file ) {
        try {
            long sizeMb = Math.max( 1, file.getContent( ).getSize( ) / ( 1024 * 1024 ) );
            double expectedMbPerSecond = 80.0;

            long seconds = (long) Math.ceil( ( sizeMb / expectedMbPerSecond ) * 1.5 );
            long clamped = Math.max( 120, Math.min( 3600, seconds ) );

            return Duration.ofSeconds( clamped );
        } catch ( Exception e ) {
            return Duration.ofMinutes( 5 );
        }
    }

    private void startProcessedCountLogger ( ) {
        processedCountTask = scheduler.scheduleAtFixedRate( ( ) -> {
            try {
                resetCounterIfNewDay( );
                logger.info( "Processed files count (last minute): {}", processedFileCount.get( ) );
            } catch ( Exception e ) {
                logger.error( "ProcessedCountLogger error {}", e.getMessage( ) );
            }
        }, 0, 60, TimeUnit.SECONDS );
    }

    private void resetCounterIfNewDay ( ) {
        LocalDate today = LocalDate.now( );
        if ( !today.equals( lastProcessedDate ) ) {
            processedFileCount.set( 0 );
            lastProcessedDate = today;

            logger.info( "resetCounterIfNewDay :: Counter reset for {}", today );
        }
    }

    public void stopWatching() {

        logger.info("RemoteDirectoryWatcher :: stopWatching");
        running.set(false);
        started.set(false);
        if (folderScanTask != null) {
            folderScanTask.cancel(true);
        }

        if (processedCountTask != null) {
            processedCountTask.cancel(true);
        }
        if (folderExecutor != null) {
            folderExecutor.shutdownNow();
            folderExecutor = null;
        }
    }

    protected void sleep ( long millis ) {
        try {
            Thread.sleep( millis );
        } catch ( InterruptedException e ) {
            Thread.currentThread( ).interrupt( );
        }
    }

    private String safeUri ( FileObject file ) {
        try {
            return file == null ? "null" : file.getName( ).getURI( );
        } catch ( Exception e ) {
            return "unknown";
        }
    }

    private void closeQuietly ( FileObject fileObject ) {
        if ( fileObject == null ) {
            return;
        }
        try {
            fileObject.close( );
        } catch ( Exception e ) {
            logger.debug( "closeQuietly :: Failed to close FileObject {}", safeUri( fileObject ) );
        }
    }

    private DicomKind classifyWithThrottle ( FileObject file ) throws Exception {
        boolean acquired = false;
        try {
            logger.debug( "classifyWithThrottle :: Waiting DICOM read permit for {}", file.getName( ).getBaseName( ) );
            acquired = dicomReadLimit.tryAcquire( 1, TimeUnit.SECONDS );
            if ( !acquired ) {
                logger.warn( "classifyWithThrottle :: No DICOM read permit available for {}. Will retry later.", file.getName( ).getBaseName( ) );
                throw new TimeoutException( "DICOM read permit unavailable" );
            }
            logger.debug( "classifyWithThrottle :: Acquired DICOM read permit for {}", file.getName( ).getBaseName( ) );
            return dicomClassifier.classify( file );
        } finally {
            if ( acquired ) {
                dicomReadLimit.release( );
                logger.debug( "classifyWithThrottle :: Released DICOM read permit for {}", file.getName( ).getBaseName( ) );
            }
        }
    }

    @PreDestroy
    public void shutdown ( ) {
        stopWatching( );
        logger.info( "RemoteDirectoryWatcher :: Shutdown initiated" );
    }
}

