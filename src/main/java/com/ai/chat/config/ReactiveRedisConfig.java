package com.ai.chat.config;

import com.ai.chat.dto.ChatMessageDto;
import com.ai.chat.dto.ChatSessionDto;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Configuration
public class ReactiveRedisConfig {

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        return new ReactiveRedisTemplate<>(factory, RedisSerializationContext.string());
    }

    @Bean("chat_message_history_cache")
    public ReactiveRedisTemplate<String, ChatMessageDto> chatHistoryRedisTemplate(
            ReactiveRedisConnectionFactory factory,
            ObjectMapper objectMapper) {

        JacksonJsonRedisSerializer<ChatMessageDto> valueSerializer =
                new JacksonJsonRedisSerializer<>(objectMapper, ChatMessageDto.class);

        RedisSerializationContext<String, ChatMessageDto> context = RedisSerializationContext
                .<String, ChatMessageDto>newSerializationContext(new StringRedisSerializer())
                .value(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean("chat_session_history_cache")
    public ReactiveRedisTemplate<String, ChatSessionDto> chatSessionHistoryRedisTemplate(
            ReactiveRedisConnectionFactory factory,
            ObjectMapper objectMapper) {

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        JacksonJsonRedisSerializer<ChatSessionDto> valueSerializer =
                new JacksonJsonRedisSerializer<>(objectMapper, ChatSessionDto.class);

        RedisSerializationContext<String, ChatSessionDto> context = RedisSerializationContext
                .<String, ChatSessionDto>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    @Primary
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName("localhost");
        config.setPort(6379);
        config.setPassword("");
        config.setDatabase(0);

        SocketOptions socketOptions = SocketOptions.builder()
                .keepAlive(true)
                .tcpNoDelay(true)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled())
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(5))
                .clientOptions(clientOptions)
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }
}