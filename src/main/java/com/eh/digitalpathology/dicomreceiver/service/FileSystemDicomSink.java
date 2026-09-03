package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.ServerConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.enums.StorageType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;

@Component
@ConditionalOnProperty( name = "server.storage.type", havingValue = "filesystem" )
public class FileSystemDicomSink implements DicomObjectSink {

    private final Path baseDir;

    public FileSystemDicomSink ( ServerConfig serverConfig ) {
        this.baseDir = Paths.get( serverConfig.getPath( ) );
    }

    @Override
    public OutputStream openStream ( String objectName ) throws IOException {
        Path targetPath = baseDir.resolve( objectName );
        Files.createDirectories( targetPath.getParent( ) );
        return new BufferedOutputStream( Files.newOutputStream( targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING ), 16 * 1024 * 1024 );
    }

    @Override
    public StorageObjectRef objectRef ( String objectName ) {
        return new StorageObjectRef( StorageType.FILESYSTEM, baseDir.toString( ), objectName );
    }


    @Override
    public InputStream openInputStream ( StorageObjectRef ref ) throws IOException {
        Path path = Paths.get( ref.uri( ) );
        return new BufferedInputStream( Files.newInputStream( path ), 16 * 1024 * 1024 );
    }

    @Override
    public void delete ( StorageObjectRef ref ) {
        try {
            Files.deleteIfExists( Paths.get( ref.uri( ) ) );
        } catch ( IOException ignored ) {
            // Exception ignored - best effort cleanup
        }
    }

    @Override
    public StorageObjectRef move ( StorageObjectRef ref, String newPrefix ) {
        try {
            Path src = Paths.get( ref.uri( ) );
            Path targetDir = src.getParent( ).resolveSibling( newPrefix );
            Files.createDirectories( targetDir );
            Path target = targetDir.resolve( src.getFileName( ) );

            Files.move( src, target, StandardCopyOption.REPLACE_EXISTING );

            return new StorageObjectRef( StorageType.FILESYSTEM, targetDir.getParent( ).toString( ), targetDir.relativize( target ).toString( ) );
        } catch ( IOException e ) {
            throw new RuntimeException( "Move failed", e );
        }
    }

}
