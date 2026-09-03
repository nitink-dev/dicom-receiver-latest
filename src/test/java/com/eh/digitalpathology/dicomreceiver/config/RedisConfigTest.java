package com.eh.digitalpathology.dicomreceiver.config;

import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisConfig without starting Spring or Redis.
 */
class RedisConfigTest {

    /**
     * Minimal ObjectProvider impl for single instance.
     */
    private static < T > ObjectProvider< T > ofSingle ( T instance ) {
        return new ObjectProvider<>( ) {
            @Override
            public T getObject ( Object... args ) {
                return instance;
            }

            @Override
            public T getIfAvailable ( ) {
                return instance;
            }

            @Override
            public T getIfUnique ( ) {
                return instance;
            }

            @Override
            public void forEach ( java.util.function.Consumer action ) {
                if ( instance != null ) action.accept( instance );
            }

            @Override
            public Stream< T > stream ( ) {
                return instance == null ? Stream.empty( ) : Stream.of( instance );
            }

            @Override
            public Stream< T > orderedStream ( ) {
                return stream( );
            }
        };
    }

    /**
     * Minimal ObjectProvider impl for multiple instances preserving order.
     */
    private static < T > ObjectProvider< T > ofList ( List< T > items ) {
        return new ObjectProvider<>( ) {
            @Override
            public T getObject ( Object... args ) {
                return items.isEmpty( ) ? null : items.get( 0 );
            }

            @Override
            public T getIfAvailable ( ) {
                return items.isEmpty( ) ? null : items.get( 0 );
            }

            @Override
            public T getIfUnique ( ) {
                return items.size( ) == 1 ? items.get( 0 ) : null;
            }

            @Override
            public void forEach ( java.util.function.Consumer action ) {
                items.forEach( action );
            }

            @Override
            public Stream< T > stream ( ) {
                return items.stream( );
            }

            @Override
            public Stream< T > orderedStream ( ) {
                return items.stream( );
            }
        };
    }

    @Test
    @DisplayName( "customRedisConnectionFactory(): applies host/port/password, clientResources, and customizers" )
    void customRedisConnectionFactory_withPassword_andCustomizers ( ) {

        RedisSentinelProps sentinelProps = new RedisSentinelProps( );
        sentinelProps.setMaster( "mymaster" );
        sentinelProps.setNodes( List.of( "127.0.0.1:26379", "127.0.0.2:26379" ) );

        // Arrange
        RedisConfig cfg = new RedisConfig( sentinelProps );
        setField( cfg, "redisPassword", "s3cr3t" );

        // Prepare a ClientResources instance
        ClientResources cr = ClientResources.builder( ).build( );

        // Two customizers; later ones may override earlier ones if they set the same option.
        List< LettuceClientConfigurationBuilderCustomizer > customizers = new ArrayList<>( );
        customizers.add( builder -> builder.commandTimeout( Duration.ofSeconds( 3 ) ) );
        customizers.add( builder -> builder.shutdownTimeout( Duration.ofSeconds( 1 ) ) );

        // Act
        LettuceConnectionFactory lcf = (LettuceConnectionFactory) cfg.sentinelRedisConnectionFactory( ofList( customizers ), ofSingle( cr ) );

        // Must initialize the LCF to populate internal config accessors
        lcf.afterPropertiesSet( );

        // Assert standalone settings

        RedisSentinelConfiguration sentinelCfg = lcf.getSentinelConfiguration( );
        assertNotNull( sentinelCfg, "Sentinel configuration should not be null" );
        assertEquals( "mymaster", sentinelCfg.getMaster( ).getName( ) );

        // Sentinel nodes
        Set< RedisNode > sentinels = sentinelCfg.getSentinels( );
        assertEquals( 2, sentinels.size( ) );

        assertTrue( sentinels.stream( ).anyMatch( n -> "127.0.0.1".equals( n.getHost( ) ) && n.getPort( ) == 26379 ) );

        RedisPassword pwd = sentinelCfg.getPassword( );
        assertNotNull( pwd );
        assertArrayEquals( "s3cr3t".toCharArray( ), pwd.get( ) );


        // Assert client configuration reflects customizers
        LettuceClientConfiguration clientCfg = lcf.getClientConfiguration( );
        assertEquals( Duration.ofSeconds( 3 ), clientCfg.getCommandTimeout( ), "commandTimeout" );
        assertEquals( Duration.ofSeconds( 1 ), clientCfg.getShutdownTimeout( ), "shutdownTimeout" );

        // clientResources presence is not directly exposed; ensure no exceptions and config reachable
        assertNotNull( clientCfg, "clientCfg should not be null" );
    }

    @Test
    @DisplayName( "customRedisConnectionFactory(): omits password if blank and still builds" )
    @Disabled( "Ignoring cleanup test temporarily" )
    void customRedisConnectionFactory_withoutPassword ( ) {
        // Arrange
        RedisSentinelProps sentinelProps = new RedisSentinelProps( );
        sentinelProps.setMaster( "mymaster" );
        sentinelProps.setNodes( List.of( "127.0.0.1:26379", "127.0.0.2:26379" ) );

        // Arrange
        RedisConfig cfg = new RedisConfig( sentinelProps );
        setField( cfg, "redisPassword", "" ); // blank → should not set password

        // No client resources, no customizers
        LettuceConnectionFactory lcf = (LettuceConnectionFactory) cfg.sentinelRedisConnectionFactory( ofList( List.of( ) ), ofSingle( null ) );

        lcf.afterPropertiesSet( );

        // Assert standalone settings
        RedisSentinelConfiguration sentinelCfg = lcf.getSentinelConfiguration( );
        assertNotNull( sentinelCfg, "Sentinel configuration should not be null" );
        assertEquals( "mymaster", sentinelCfg.getMaster( ).getName( ) );

        // Sentinel nodes
        Set< RedisNode > sentinels = sentinelCfg.getSentinels( );
        assertEquals( 2, sentinels.size( ) );

        assertTrue( sentinels.stream( ).anyMatch( n -> "127.0.0.1".equals( n.getHost( ) ) && n.getPort( ) == 26379 ) );

        RedisPassword pwd = sentinelCfg.getPassword( );
        assertNull( pwd, "password should be null when blank input" );
    }

    @Test
    @DisplayName( "redisTemplate(): uses provided factory and sets String/JSON serializers" )
    void redisTemplate_serializersAndFactory ( ) {
        // Build a minimal factory from config
        RedisSentinelProps sentinelProps = new RedisSentinelProps( );
        sentinelProps.setMaster( "mymaster" );
        sentinelProps.setNodes( List.of( "127.0.0.1:26379", "127.0.0.2:26379" ) );

        // Arrange
        RedisConfig cfg = new RedisConfig( sentinelProps );
        setField( cfg, "redisPassword", "s3cr3t" );

        LettuceConnectionFactory lcf = (LettuceConnectionFactory) cfg.sentinelRedisConnectionFactory( ofList( List.of( ) ), ofSingle( null ) );
        lcf.afterPropertiesSet( );

        // Act
        RedisTemplate< String, Object > template = cfg.redisTemplate( lcf );

        // Assert connection factory wired
        assertSame( lcf, template.getConnectionFactory( ) );

        // Assert serializers
        assertInstanceOf( StringRedisSerializer.class, template.getKeySerializer( ) );
        assertInstanceOf( StringRedisSerializer.class, template.getHashKeySerializer( ) );
        assertInstanceOf( GenericJackson2JsonRedisSerializer.class, template.getValueSerializer( ) );
        assertInstanceOf( GenericJackson2JsonRedisSerializer.class, template.getHashValueSerializer( ) );
    }

    // ---- helpers ----
    private static void setField ( Object target, String name, Object value ) {
        try {
            var f = target.getClass( ).getDeclaredField( name );
            f.setAccessible( true );
            f.set( target, value );
        } catch ( ReflectiveOperationException e ) {
            throw new AssertionError( "Failed to set field '" + name + "'", e );
        }
    }
}