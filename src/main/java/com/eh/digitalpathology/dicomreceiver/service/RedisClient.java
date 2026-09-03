package com.eh.digitalpathology.dicomreceiver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public class RedisClient {

    private static final Logger log = LoggerFactory.getLogger( RedisClient.class );

    private static final Duration FILE_PROCESSED_TTL = Duration.ofHours( 12 );
    private static final Duration FILE_LOCK_DEFAULT_TTL = Duration.ofMinutes( 5 );
    private static final Duration FOLDER_COMPLETED_TTL = Duration.ofHours( 12 );

    /*
     * Keep this high enough for large folders.
     * If a folder can take more than 30 minutes, 30 minutes can expire
     * while the folder is still processing and scanner may enqueue it again.
     */
    private static final Duration FOLDER_LOCK_TTL = Duration.ofHours( 2 );
    private final RedisTemplate< String, Object > redisTemplate;

    public RedisClient ( RedisTemplate< String, Object > redisTemplate ) {
        this.redisTemplate = redisTemplate;
    }

    // =========================================================
    // KEYS
    // =========================================================

    private String processedKey ( String key ) {
        return "file:processed:" + key;
    }

    private String fileLockKey ( String key ) {
        return "file:lock:" + key;
    }

    private String folderQueueKey ( ) {
        return "folder:queue";
    }

    private String folderLockKey ( String path ) {
        return "folder:lock:" + path;
    }

    private String folderCompletedKey ( String path ) {
        return "folder:completed:" + path;
    }

    // =========================================================
    // FILE OPERATIONS
    // =========================================================

    public boolean isFileProcessed ( String key ) {
        try {
            return Boolean.TRUE.equals( redisTemplate.hasKey( processedKey( key ) ) );
        } catch ( Exception ex ) {
            log.error( "isFileProcessed :: Failed for file {} : {}", key, ex.getMessage( ), ex );
            return false;
        }
    }

    public void markFileAsProcessed ( String key ) {
        try {
            redisTemplate.opsForValue( ).set( processedKey( key ), "1", FILE_PROCESSED_TTL );
        } catch ( Exception ex ) {
            log.error( "markFileAsProcessed :: Failed for file {} : {}", key, ex.getMessage( ), ex );
        }
    }

    public boolean tryLockFile ( String key, Duration ttl ) {
        try {
            Duration effectiveTtl = ttl == null ? FILE_LOCK_DEFAULT_TTL : ttl;
            return Boolean.TRUE.equals( redisTemplate.opsForValue( ).setIfAbsent( fileLockKey( key ), "1", effectiveTtl ) );

        } catch ( Exception ex ) {
            log.error( "tryLockFile :: Failed to acquire lock for file {} : {}", key, ex.getMessage( ), ex );
            return false;
        }
    }

    public void releaseFileLock ( String key ) {
        try {
            redisTemplate.delete( fileLockKey( key ) );
        } catch ( Exception ex ) {
            log.error( "releaseFileLock :: Failed for file {} : {}", key, ex.getMessage( ), ex );
        }
    }

    // =========================================================
    // FOLDER STATE CHECKS
    // =========================================================

    public boolean isFolderQueued ( String path ) {
        try {
            return Boolean.TRUE.equals( redisTemplate.opsForSet( ).isMember( folderQueueKey( ), path ) );
        } catch ( Exception ex ) {
            log.error( "isFolderQueued :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
            return false;
        }
    }

    public boolean isFolderLocked ( String path ) {
        try {
            return Boolean.TRUE.equals( redisTemplate.hasKey( folderLockKey( path ) ) );
        } catch ( Exception ex ) {
            log.error( "isFolderLocked :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
            return false;
        }
    }

    public boolean isFolderCompleted ( String path ) {
        try {
            return Boolean.TRUE.equals( redisTemplate.hasKey( folderCompletedKey( path ) ) );
        } catch ( Exception ex ) {
            log.error( "isFolderCompleted :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
            return false;
        }
    }

    public void clearFolderCompleted ( String path ) {
        try {
            redisTemplate.delete( folderCompletedKey( path ) );
        } catch ( Exception ex ) {
            log.error( "clearFolderCompleted :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
        }
    }

    // =========================================================
    // FOLDER QUEUE
    // =========================================================

    public boolean enqueueFolder ( String path ) {
        try {
            /*
             * Important:
             * This prevents repeated enqueue while folder is already waiting.
             */
            if ( isFolderQueued( path ) ) {
                log.debug( "enqueueFolder :: Folder already queued. Skipping {}", path );
                return false;
            }

            /*
             * Important:
             * This prevents enqueue while one folder worker is already processing it.
             */
            if ( isFolderLocked( path ) ) {
                log.debug( "enqueueFolder :: Folder already locked/processing. Skipping {}", path );
                return false;
            }
            /*
             * Important:
             * RemoteDirectoryWatcher clears completed state if new unprocessed files are found.
             * So if completed still exists here, it means no new file was detected.
             */
            if ( isFolderCompleted( path ) ) {
                log.debug( "enqueueFolder :: Folder already completed. Skipping {}", path );
                return false;
            }

            Long added = redisTemplate.opsForSet( ).add( folderQueueKey( ), path );
            boolean enqueued = Long.valueOf( 1L ).equals( added );
            if ( enqueued ) {
                log.debug( "enqueueFolder :: Folder enqueued {}", path );
            } else {
                log.debug( "enqueueFolder :: Folder not enqueued, probably already present {}", path );
            }
            return enqueued;
        } catch ( Exception ex ) {
            log.error( "enqueueFolder :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
            return false;
        }
    }

    public String dequeueFolder ( ) {
        try {
            Set< Object > items = redisTemplate.opsForSet( ).members( folderQueueKey( ) );
            if ( items == null || items.isEmpty( ) ) {
                return null;
            }
            for ( Object obj : items ) {
                if ( obj == null ) {
                    continue;
                }
                String path = obj.toString( );
                /*
                 * If completed folder is somehow still in queue, remove it.
                 */
                if ( isFolderCompleted( path ) ) {
                    redisTemplate.opsForSet( ).remove( folderQueueKey( ), path );
                    log.debug( "dequeueFolder :: Removed completed folder from queue {}", path );
                    continue;
                }
                /*
                 * Try to acquire distributed folder lock.
                 * Only the worker that gets the lock removes the folder from queue.
                 */
                if ( tryLockFolder( path ) ) {
                    redisTemplate.opsForSet( ).remove( folderQueueKey( ), path );
                    log.debug( "dequeueFolder :: Dequeued and locked folder {}", path );
                    return path;
                }
            }
            return null;
        } catch ( Exception ex ) {
            log.error( "dequeueFolder :: Failed to dequeue folder : {}", ex.getMessage( ), ex );
            return null;
        }
    }

    // =========================================================
    // FOLDER LOCK
    // =========================================================

    public boolean tryLockFolder ( String path ) {
        try {
            return Boolean.TRUE.equals( redisTemplate.opsForValue( ).setIfAbsent( folderLockKey( path ), "1", FOLDER_LOCK_TTL ) );
        } catch ( Exception ex ) {
            log.error( "tryLockFolder :: Failed to acquire lock for folder {} : {}", path, ex.getMessage( ), ex );
            return false;
        }
    }

    public void releaseFolderLock ( String path ) {
        try {
            redisTemplate.delete( folderLockKey( path ) );
        } catch ( Exception ex ) {
            log.error( "releaseFolderLock :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
        }
    }

    // =========================================================
    // FOLDER COMPLETED
    // =========================================================

    public void markFolderCompleted ( String path ) {
        try {
            redisTemplate.opsForValue( ).set( folderCompletedKey( path ), "DONE", FOLDER_COMPLETED_TTL );
        } catch ( Exception ex ) {
            log.error( "markFolderCompleted :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
        }
    }

    public void extendFileLock ( String key, Duration ttl ) {
        try {
            String lockKey = fileLockKey( key );
            Boolean exists = redisTemplate.hasKey( lockKey );
            if ( Boolean.TRUE.equals( exists ) ) {
                redisTemplate.expire( lockKey, ttl );
                log.debug( "extendFileLock :: Extended lock for file {} by {}", key, ttl );
            }

        } catch ( Exception ex ) {
            log.error( "extendFileLock :: Failed for file {} : {}", key, ex.getMessage( ), ex );
        }
    }

    public void extendFolderLock ( String path, Duration ttl ) {
        try {
            String lockKey = folderLockKey( path );
            Boolean exists = redisTemplate.hasKey( lockKey );
            if ( Boolean.TRUE.equals( exists ) ) {
                redisTemplate.expire( lockKey, ttl );
                log.debug( "extendFolderLock :: Extended lock for folder {} by {}", path, ttl );
            }
        } catch ( Exception ex ) {
            log.error( "extendFolderLock :: Failed for folder {} : {}", path, ex.getMessage( ), ex );
        }
    }
}

