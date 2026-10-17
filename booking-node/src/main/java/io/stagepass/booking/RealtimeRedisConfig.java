package io.stagepass.booking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RealtimeRedisConfig {
    @Bean
    RedisMessageListenerContainer realtimeListener(RedisConnectionFactory factory, RealtimeService realtime) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(realtime, new ChannelTopic("stagepass:events:live"));
        return container;
    }
}
