package com.eh.digitalpathology.dicomreceiver.config;

import org.dcm4che3.data.UID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SopClassPolicy {

    private static final Logger log = LoggerFactory.getLogger( SopClassPolicy.class.getName( ) );
    private final Set<String> allowedSopClassUids;
    public SopClassPolicy ( ) throws IOException {
        Properties props = new Properties( );
        try ( InputStream is = getClass( ).getClassLoader( ).getResourceAsStream( "sop-classes.properties" ) ) {

            if ( is == null ) {
                throw new IllegalStateException( "sop-classes.properties not found" );
            }
            props.load( is );
        }

        this.allowedSopClassUids = props.keySet( ).stream( ).map( Object::toString ).map( this::resolveToUid ).filter( Objects::nonNull ).collect( Collectors.toUnmodifiableSet( ) );

        log.info( "Allowed SOP Classes for ingestion: {}", allowedSopClassUids );
    }

    private String resolveToUid ( String sopClassName ) {
        try {
            return (String) UID.class.getField( sopClassName ).get( null );
        } catch ( Exception e ) {
            log.error( "Unknown SOP Class name in config: {}", sopClassName );
            return null;
        }
    }

    public boolean isAllowed ( String sopClassUid ) {
        return allowedSopClassUids.contains( sopClassUid );
    }
}


