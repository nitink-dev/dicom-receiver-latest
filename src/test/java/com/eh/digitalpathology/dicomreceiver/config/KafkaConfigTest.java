package com.eh.digitalpathology.dicomreceiver.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConfigTest {

    private static void setField ( Object target, String fieldName, Object value ) throws Exception {
        Field f = target.getClass( ).getDeclaredField( fieldName );
        f.setAccessible( true );
        f.set( target, value );
    }

    @Test
    @DisplayName( "producerFactory(): config map has bootstrap servers, serializers, acks, idempotence" )
    void producerFactory_hasExpectedProperties ( ) throws Exception {

        KafkaProperties kafkaProperties = new KafkaProperties( );

        kafkaProperties.setBootstrapServers( List.of( "localhost:9092" ) );

        kafkaProperties.getProducer( ).setAcks( "all" );
        kafkaProperties.getProducer( ).setKeySerializer( org.apache.kafka.common.serialization.StringSerializer.class );
        kafkaProperties.getProducer( ).setValueSerializer( org.apache.kafka.common.serialization.StringSerializer.class );

        kafkaProperties.getProperties().put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.toString( true )
        );

        KafkaConfig config = new KafkaConfig( kafkaProperties );


        ProducerFactory< String, String > pf = config.producerFactory( );
        assertNotNull( pf );
        assertInstanceOf( DefaultKafkaProducerFactory.class, pf );

        Map< String, Object > props = ( (DefaultKafkaProducerFactory< String, String >) pf ).getConfigurationProperties( );
        assertEquals( List.of("localhost:9092"), props.get( ProducerConfig.BOOTSTRAP_SERVERS_CONFIG ) );
        assertEquals( StringSerializer.class, props.get( ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG ) );
        assertEquals( StringSerializer.class, props.get( ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG ) );
        assertEquals( "all", props.get( ProducerConfig.ACKS_CONFIG ) );
        assertEquals( "true", props.get( ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG ) );
    }

    @Test
    @DisplayName( "customKafkaTemplate(): is created from the configured producerFactory" )
    void customKafkaTemplate_constructs ( ) throws Exception {
        KafkaProperties kafkaProperties = new KafkaProperties( );

        kafkaProperties.setBootstrapServers( List.of( "broker:9092" ) );

        kafkaProperties.getProducer( ).setAcks( "1" );
        kafkaProperties.getProducer( ).setKeySerializer( org.apache.kafka.common.serialization.StringSerializer.class );
        kafkaProperties.getProducer( ).setValueSerializer( org.apache.kafka.common.serialization.StringSerializer.class );

        kafkaProperties.getProperties().put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.toString( false )
        );

        KafkaConfig config = new KafkaConfig( kafkaProperties );

        KafkaTemplate< String, String > tpl = config.customKafkaTemplate( );
        assertNotNull( tpl );
    }

    @Test
    @DisplayName( "kafkaListenerContainerFactory(): sets MANUAL_IMMEDIATE ack mode" )
    void kafkaListenerContainerFactory_setsAckMode ( ) throws Exception {
        KafkaProperties kafkaProperties = new KafkaProperties( );

        kafkaProperties.setBootstrapServers( List.of( "broker:9092" ) );

        kafkaProperties.getProducer( ).setAcks( "all" );
        kafkaProperties.getProducer( ).setKeySerializer( org.apache.kafka.common.serialization.StringSerializer.class );
        kafkaProperties.getProducer( ).setValueSerializer( org.apache.kafka.common.serialization.StringSerializer.class );
        kafkaProperties.getListener().setAckMode( ContainerProperties.AckMode.MANUAL_IMMEDIATE );

        kafkaProperties.getProperties().put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.toString( true )
        );
        KafkaConfig config = new KafkaConfig( kafkaProperties );

        ConcurrentKafkaListenerContainerFactory< String, String > factory = config.kafkaListenerContainerFactory( );
        assertNotNull( factory );
        assertEquals( org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE, factory.getContainerProperties( ).getAckMode( ) );
    }
}
