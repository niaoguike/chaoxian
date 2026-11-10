package com.hmdp.config;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.*;
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod="shutdown")
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        org.redisson.config.SingleServerConfig server = config.useSingleServer()
            .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
            .setDatabase(properties.getDatabase());
        if(properties.getPassword()!=null && !properties.getPassword().isEmpty()) server.setPassword(properties.getPassword());
        return Redisson.create(config);
    }
}
