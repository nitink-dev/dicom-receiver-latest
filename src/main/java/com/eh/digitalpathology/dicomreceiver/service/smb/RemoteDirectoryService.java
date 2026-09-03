package com.eh.digitalpathology.dicomreceiver.service.smb;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.SharedFolderConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.service.DicomExtractorService;
import com.eh.digitalpathology.dicomreceiver.service.MetadataDispatcher;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.*;


@Service
@RefreshScope
public class RemoteDirectoryService {

    private static final Logger logger = LoggerFactory.getLogger( RemoteDirectoryService.class );

    @Value( "${max.retries}" )
    private long maxRetries;

    private final ExecutorService executorService;
    private final SharedFolderConfig sharedFolderConfig;
    private final MetadataDispatcher metadataDispatcher;
    private final DicomObjectSink dicomObjectSink;
    private final DicomExtractorService dicomExtractorService;

    private final ScheduledExecutorService deleteSchedular;


    private final Semaphore largeCopyLimit = new Semaphore( 2, true ); // > 1 GB
    private final Semaphore mediumCopyLimit = new Semaphore( 6, true ); // 100 MB – 1 GB

    public RemoteDirectoryService ( @Qualifier( "remoteDirectoryWatcherExecutor" ) ExecutorService executorService, SharedFolderConfig sharedFolderConfig, MetadataDispatcher metadataDispatcher, DicomObjectSink dicomObjectSink, DicomExtractorService dicomExtractorService, @Qualifier(
            "deleteSchedular" ) ScheduledExecutorService deleteSchedular ) {
        this.executorService = executorService;
        this.sharedFolderConfig = sharedFolderConfig;

        this.metadataDispatcher = metadataDispatcher;
        this.dicomObjectSink = dicomObjectSink;
        this.dicomExtractorService = dicomExtractorService;
        this.deleteSchedular = deleteSchedular;
    }

    public CompletableFuture< Boolean > processFileEvent ( FileObject file, String smbUrl ) {
        logger.info( "processFileEvent :: Inside processFileEvent method for file: {}", file );
        try {
            String baseName = file.getName( ).getBaseName( );
            boolean isDicomDir = "DICOMDIR".equalsIgnoreCase( baseName );
            String relativeFilePath = file.getName( ).getPath( ).replaceFirst( smbUrl, "" );
            long sizeBytes = safeSize( file );

            return CompletableFuture.supplyAsync( ( ) -> executeWithSizeControl( sizeBytes, ( ) -> establishConnectionAndCopy( relativeFilePath, baseName, sizeBytes, isDicomDir ) ), executorService );

        } catch ( Exception e ) {
            logger.error( "processFileEvent :: Exception occurred while processing file :: {}", e.getMessage() );
            return CompletableFuture.failedFuture( e );
        }
    }

    private Boolean executeWithSizeControl ( long sizeBytes, Callable< Boolean > task ) {
        Semaphore semaphore = null;
        try {
            if ( sizeBytes > ( 1L << 30 ) ) {
                semaphore = largeCopyLimit;
                logger.info( "executeWithSizeControl :: waiting LARGE permit size={} MB thread={}" ,  sizeBytes / (1024 * 1024), Thread.currentThread().getName());
            } else if ( sizeBytes > ( 300L << 20 ) ) {
                semaphore = mediumCopyLimit;
                logger.info("executeWithSizeControl :: waiting MEDIUM permit size={} MB thread={}",  sizeBytes / (1024 * 1024), Thread.currentThread().getName());
            }
            if ( semaphore != null ) {
                if ( !semaphore.tryAcquire( 5, TimeUnit.MINUTES ) ) {
                    logger.warn( "executeWithSizeControl :: Timed out waiting for permit" );
                    return false;
                }
                logger.info("executeWithSizeControl :: acquired permit size={} MB thread={}", sizeBytes / (1024 * 1024), Thread.currentThread().getName());
            }
            return task.call( );
        } catch ( Exception e ) {
            logger.error( "executeWithSizeControl :: Error :: {}", e.getMessage() );
            return false;
        } finally {
            if ( semaphore != null ) {
                semaphore.release( );
            }
        }
    }


    private long safeSize ( FileObject file ) {
        try {
            return file.getContent( ).getSize( );
        } catch ( Exception e ) {
            logger.warn( "safeSize :: Unable to read size; assuming medium {}", e.getMessage( ) );
            return 200L << 20; // 200 MB default
        }
    }

    protected Boolean establishConnectionAndCopy ( String remoteFileRelativePath, String remoteFileName, long sizeBytes, boolean isDicomDir ) {
        logger.info( "establishConnectionAndProcessFile :: relative path : {}", remoteFileRelativePath );

        int attempts = 0;
        final int maxCopyRetries = Math.min( 2, (int) maxRetries );
        while ( ++attempts <= maxCopyRetries ) {
            try ( SMBClient client = new SMBClient( smbConfig( ) ); Connection connection = client.connect( sharedFolderConfig.getServername( ) ); Session session = connection.authenticate( authContext( ) ); DiskShare share = (DiskShare) session.connectShare( sharedFolderConfig.getSharepath( ) ) ) {
                if ( !share.fileExists( remoteFileRelativePath ) ) {
                    logger.info( "establishConnectionAndCopy :: SMB file vanished: {}", remoteFileRelativePath );
                    return true;
                }
                try ( File remoteFile = openRemoteFile( remoteFileRelativePath, share ) ) {
                    if ( isDicomDir ) {
                        boolean processed = handleDicomDir( remoteFile, remoteFileRelativePath );
                        scheduleDelete( remoteFileRelativePath ); // best effort cleanup
                        return processed;
                    }
                    return copyAndDeleteFile( remoteFileRelativePath, remoteFileName, sizeBytes, remoteFile );
                }
            } catch ( TransportException | SocketTimeoutException ex ) {
                logger.error( "establishConnectionAndProcessFile :: SMB transport closed for {} (attempt {}): {}", remoteFileName, attempts, ex.getMessage( ) );
                return true;

            } catch ( Exception e ) {
                logger.error( "establishConnectionAndProcessFile :: Attempt {} - Exception during open/copy: {}", attempts, e.getMessage( ) );
                // On SMB errors, drop share so next attempt reacquires
                if ( shouldRetry( remoteFileName, attempts, maxCopyRetries ) ) return false;
                if ( sleepQuietly( 500L ) ) return false;
                // reacquire share for next attempt
            }
        }
        return false;
    }

    private SmbConfig smbConfig ( ) {
        return SmbConfig.builder( ).withTimeout( 60, TimeUnit.SECONDS )         // ✅ server response
                .withSoTimeout( 120_000 )                    // ✅ socket timeout
                .withReadBufferSize( 16 * 1024 * 1024 )       // ✅ 4 MB
                .withWriteBufferSize( 16 * 1024 * 1024 ).build( );

    }

    private AuthenticationContext authContext ( ) {
        return StringUtils.isNotBlank( sharedFolderConfig.getUsername( ) ) ? new AuthenticationContext( sharedFolderConfig.getUsername( ), sharedFolderConfig.getPassword( ).toCharArray( ), "" ) : AuthenticationContext.anonymous( );
    }

    private boolean shouldRetry ( String remoteFileName, int attempts, int maxCopyRetries ) {
        if ( attempts > maxCopyRetries ) {
            logger.error( "establishConnectionAndProcessFile :: Max retries reached for {}", remoteFileName );
            return true;
        }
        return false;
    }

    private Boolean copyAndDeleteFile ( String remoteFileRelativePath, String remoteFileName, long sizeBytes, File remoteFile ) {
        boolean copied = copyRemoteFileToLocal( remoteFile, remoteFileName, sizeBytes );
        if ( !copied ) {
            return false;
        }
        // Optional delete after copy; wrap in try/catch to avoid blocking success path
        scheduleDelete( remoteFileRelativePath );
        return true;
    }

    private void scheduleDelete ( String remoteFileRelativePath ) {
        deleteSchedular.schedule( ( ) -> {
            try ( SMBClient client = new SMBClient( smbConfig( ) ); Connection connection = client.connect( sharedFolderConfig.getServername( ) ); Session session = connection.authenticate( authContext( ) ); DiskShare share = (DiskShare) session.connectShare( sharedFolderConfig.getSharepath( ) ) ) {
                boolean deleted = deleteWithRetry( share, remoteFileRelativePath );

                if ( deleted ) {
                    logger.info( "scheduleDelete :: Successfully deleted {}", remoteFileRelativePath );
                } else {
                    logger.warn( "scheduleDelete :: Failed to delete {}", remoteFileRelativePath );
                }
            } catch ( Exception e ) {
                logger.error( "scheduleDelete :: Error deleting {} : {}", remoteFileRelativePath, e.getMessage( ));
            }
        }, 15, TimeUnit.SECONDS ); // ✅ 15 seconds delay
    }

    public void scheduleDeleteFolderIfEmpty ( String folderPathOrUri, String smbUrl ) {
        if ( StringUtils.isBlank( folderPathOrUri ) ) {
            logger.warn( "scheduleDeleteFolderIfEmpty :: Folder path is blank. Skipping folder delete." );
            return;
        }
        deleteSchedular.schedule( ( ) -> {
            try {
                String relativeFolderPath = toShareRelativePath( folderPathOrUri, smbUrl );

                if ( StringUtils.isBlank( relativeFolderPath ) ) {
                    logger.warn( "scheduleDeleteFolderIfEmpty :: Relative folder path is blank for {}. Skipping.", folderPathOrUri );
                    return;
                }
                boolean deleted = deleteFolderIfEmptyWithRetry( relativeFolderPath );
                if ( deleted ) {
                    logger.info( "scheduleDeleteFolderIfEmpty :: Successfully deleted empty folder {}", relativeFolderPath );
                } else {
                    logger.warn( "scheduleDeleteFolderIfEmpty :: Folder not deleted. It may still contain files: {}", relativeFolderPath );
                }
            } catch ( Exception e ) {
                logger.error( "scheduleDeleteFolderIfEmpty :: Error while deleting folder {} : {}", folderPathOrUri, e.getMessage( ));
            }
        }, 75L, TimeUnit.SECONDS );
    }

    private boolean deleteFolderIfEmptyWithRetry ( String relativeFolderPath ) {
        for ( int attempt = 1; attempt <= 5; attempt++ ) {
            try ( SMBClient client = new SMBClient( smbConfig( ) ); Connection connection = client.connect( sharedFolderConfig.getServername( ) ); Session session = connection.authenticate( authContext( ) ); DiskShare share = (DiskShare) session.connectShare( sharedFolderConfig.getSharepath( ) ) ) {

                if ( !share.folderExists( relativeFolderPath ) ) {
                    logger.info( "deleteFolderIfEmptyWithRetry :: Folder already deleted or does not exist {}", relativeFolderPath );
                    return true;
                }
                if ( !isFolderEmpty( share, relativeFolderPath ) ) {
                    logger.info( "deleteFolderIfEmptyWithRetry :: Folder is not empty on attempt {}/{}: {}", attempt, 5, relativeFolderPath );

                    if ( attempt < 5 ) {
                        if ( sleepQuietly( 15000L ) ) {
                            return false;
                        }
                        continue;
                    }
                    return false;
                }
                /*
                 * SMBJ version note:
                 * Most SMBJ versions support rmdir(path, recursive).
                 * recursive=false ensures we delete only an empty folder.
                 */
                share.rmdir( relativeFolderPath, false );
                logger.info( "deleteFolderIfEmptyWithRetry :: Empty folder deleted {}", relativeFolderPath );
                return true;
            } catch ( Exception e ) {
                String msg = String.valueOf( e.getMessage( ) );
                boolean retryable = msg.contains( "STATUS_SHARING_VIOLATION" ) || msg.contains( "0xc0000043" ) || msg.contains( "STATUS_ACCESS_DENIED" ) || msg.contains( "0xc0000022" ) || msg.contains( "STATUS_DIRECTORY_NOT_EMPTY" ) || msg.contains( "0xc0000101" );
                logger.warn( "deleteFolderIfEmptyWithRetry :: Attempt {}/{} failed for folder {} : {}", attempt, 5, relativeFolderPath, msg );
                if ( !retryable ) {
                    return false;
                }
                if ( attempt < 5 ) {
                    if ( sleepQuietly( 15000 ) ) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private boolean isFolderEmpty ( DiskShare share, String relativeFolderPath ) {
        try {
            List< FileIdBothDirectoryInformation > entries = share.list( relativeFolderPath );
            if ( entries == null || entries.isEmpty( ) ) {
                return true;
            }
            for ( FileIdBothDirectoryInformation entry : entries ) {
                String fileName = entry.getFileName( );
                if ( ".".equals( fileName ) || "..".equals( fileName ) ) {
                    continue;
                }
                logger.debug( "isFolderEmpty :: Folder {} still contains entry {}", relativeFolderPath, fileName );
                return false;
            }
            return true;
        } catch ( Exception e ) {
            logger.warn( "isFolderEmpty :: Unable to list folder {} : {}", relativeFolderPath, e.getMessage( ) );
            return false;
        }
    }

    private String toShareRelativePath ( String pathOrUri, String smbUrl ) {
        try {
            if ( StringUtils.isBlank( pathOrUri ) ) {
                return "";
            }
            String normalized = pathOrUri.trim( ).replace( "\\", "/" );
            /*
             * Case 1:
             * Full SMB URI starting with watched smbUrl.
             */
            if ( StringUtils.isNotBlank( smbUrl ) ) {
                String normalizedSmbUrl = smbUrl.trim( ).replace( "\\", "/" );
                if ( normalized.startsWith( normalizedSmbUrl ) ) {
                    normalized = normalized.substring( normalizedSmbUrl.length( ) );
                }
            }
            /*
             * Case 2:
             * Full SMB URI but not directly matched with smbUrl.
             */
            if ( normalized.startsWith( "smb://" ) ) {
                URI uri = URI.create( normalized );
                normalized = uri.getPath( );
            }
            /*
             * Remove share path if present.
             *
             * Example:
             * /Test-Shared/2026-06-11/SERIES_X
             * becomes:
             * 2026-06-11/SERIES_X
             */
            String sharePath = sharedFolderConfig.getSharepath( );
            if ( StringUtils.isNotBlank( sharePath ) ) {
                String normalizedSharePath = sharePath.trim( ).replace( "\\", "/" );
                if ( !normalizedSharePath.startsWith( "/" ) ) {
                    normalizedSharePath = "/" + normalizedSharePath;
                }
                if ( normalized.startsWith( normalizedSharePath ) ) {
                    normalized = normalized.substring( normalizedSharePath.length( ) );
                }
            }

            while ( normalized.startsWith( "/" ) ) {
                normalized = normalized.substring( 1 );
            }

            while ( normalized.endsWith( "/" ) ) {
                normalized = normalized.substring( 0, normalized.length( ) - 1 );
            }
            return normalized;
        } catch ( Exception e ) {
            logger.error( "toShareRelativePath :: Failed to convert path {} using smbUrl {} : {}", pathOrUri, smbUrl, e.getMessage( ) );
            return "";
        }
    }


    private File openRemoteFile ( String remoteFileRelativePath, DiskShare handle ) {
        return handle.openFile( remoteFileRelativePath, EnumSet.of( AccessMask.GENERIC_READ ), EnumSet.noneOf( FileAttributes.class ), EnumSet.of( SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_DELETE, SMB2ShareAccess.FILE_SHARE_WRITE ), SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf( SMB2CreateOptions.class ) );
    }

    private boolean sleepQuietly ( long millis ) {
        try {
            Thread.sleep( millis );
        } catch ( InterruptedException ie ) {
            Thread.currentThread( ).interrupt( );
            return true;
        }
        return false;
    }

    /**
     * Delete with small retry/backoff to handle transient sharing violations
     */
    private boolean deleteWithRetry ( DiskShare share, String remoteFileRelativePath ) {
        for ( int i = 1; i <= Math.max( 1, 3 ); i++ ) {
            try {
                share.rm( remoteFileRelativePath );
                return true;
            } catch ( Exception e ) {
                String msg = String.valueOf( e.getMessage( ) );
                boolean sharingViolation = msg.contains( "STATUS_SHARING_VIOLATION" ) || msg.contains( "0xc0000043" ) || msg.contains( "STATUS_ACCESS_DENIED" ) ||        // ✅ ADD THIS
                        msg.contains( "0xc0000022" );
                if ( !sharingViolation ) {
                    logger.warn( "deleteWithRetry :: Delete failed (non-sharing) for {}: {}", remoteFileRelativePath, msg );
                    return false;
                }
                logger.warn( "deleteWithRetry :: Sharing violation on attempt {}/{} for {}. Retrying...", i, 3, remoteFileRelativePath );
                if ( i < 3 ) {
                    if ( sleepQuietly( 1500 ) ) return false;
                }
            }
        }
        return false;
    }


    private static int bufferSizeFor ( long sizeBytes ) {
        final int MB = 1024 * 1024;

        if ( sizeBytes <= 100L * MB ) {          // ≤100 MB
            return 16 * MB;
        } else if ( sizeBytes <= (long) 1024 * MB ) { // ≤1 GB
            return 32 * MB;
        } else {                               // >1 GB
            return 64 * MB; // ✅ safe upper bound in GKE
        }
    }

    private boolean handleDicomDir ( File remoteFile, String remoteFileRelativePath ) {
        try ( InputStream smbIn = new BufferedInputStream( remoteFile.getInputStream( ), 16 * 1024 * 1024 ) ) {
            dicomExtractorService.handleDicomDir( smbIn, remoteFileRelativePath );
            return true;
        } catch ( Exception e ) {
            logger.error( "Failed to process DICOMDIR {}", e.getMessage( ) );
            return false;
        }
    }


    private boolean copyRemoteFileToLocal ( File remoteFile, String remoteFileName, long sizeBytes ) {

        logger.info( "copyRemoteFileToLocal :: Copying file: {} of size :: {},  thread:: {}", remoteFileName, sizeBytes >> 20, Thread.currentThread().getName() );

        int bufferSize = bufferSizeFor( sizeBytes );
        long startTimeNs = System.nanoTime( );
        try ( InputStream smbIn = new BufferedInputStream( remoteFile.getInputStream( ), bufferSize ); OutputStream out = dicomObjectSink.openStream( remoteFileName ) ) {
            byte[] buffer = new byte[ bufferSize ];
            int read;

            while ( ( read = smbIn.read( buffer ) ) != -1 ) {
                out.write( buffer, 0, read );
            }
        } catch ( Exception e ) {
            logger.error( "Upload failed: {}::{}", remoteFileName, e.getMessage() );
            return false;
        }

        long endTimeNs = System.nanoTime( );
        double timeTakenSeconds = ( endTimeNs - startTimeNs ) / 1_000_000_000.0;

        logger.info( "copyRemoteFileToLocal :: Completed copying file: {} of size :: {} bytes, time taken :: {} seconds", remoteFileName, sizeBytes >> 20, String.format( "%.3f", timeTakenSeconds ) );

        StorageObjectRef objectRef = dicomObjectSink.objectRef( remoteFileName );
        // ✅ Hand off asynchronously — watcher is FREE now
        metadataDispatcher.submit( objectRef );
        return true;
    }

}
