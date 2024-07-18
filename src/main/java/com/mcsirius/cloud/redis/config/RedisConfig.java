package com.mcsirius.cloud.redis.config;

import org.springframework.data.redis.cache.TTCacheManager;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
@Slf4j
@EnableConfigurationProperties(CacheProperties.class)
public class RedisConfig {
    /**
     * 自定义key规则
     * @return
     */
    @Bean
    public KeyGenerator keyGenerator() {
        return (target, method, params) -> {
            StringBuilder sb = new StringBuilder();
            sb.append(target.getClass().getName());
            sb.append(method.getName());
            for (Object obj : params) {
                sb.append(obj.toString());
            }
            return sb.toString();
        };
    }

    /**
     * 设置RedisTemplate规则
     * @param redisConnectionFactory
     * @return
     */
    @Bean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        //使用StringRedisSerializer来序列化和反序列化redis的key值
        template.setKeySerializer(stringRedisSerializer);
        // hash的key也采用String的序列化方式
        template.setHashKeySerializer(stringRedisSerializer);
        // value序列化方式采用jackson
        template.setValueSerializer(jackson2JsonRedisSerializer());
        // hash的value序列化方式采用jackson
        template.setHashValueSerializer(jackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }


    /**
     * 设置CacheManager缓存规则
     * @param redisConnectionFactory
     * @param cacheProperties
     * @return
     */
    @Bean(name = "redisCacheManager")
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory, CacheProperties cacheProperties) {
        return TTCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(redisCacheConfiguration(cacheProperties))
                .build();
    }

    @Bean(name = "redisCacheConfiguration")
    public RedisCacheConfiguration redisCacheConfiguration(CacheProperties cacheProperties){
        //在默认配置上修改，也可以new一个。指定序列化方式！
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig().
                serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())).
                serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jackson2JsonRedisSerializer()));

        //从容器中取得redisProperties并进行判断，不然在application.yml配置的文件无效。
        // 因为只有默认的RedisCacheConfiguration才会走下面的内容，自定义的需要cv大法后改造一下
        if (null != cacheProperties){
            CacheProperties.Redis redisProperties = cacheProperties.getRedis();
            if (redisProperties.getTimeToLive() != null) {
                config = config.entryTtl(redisProperties.getTimeToLive());
            }
            if (!redisProperties.isCacheNullValues()) {
                config = config.disableCachingNullValues();
            }
            if (!redisProperties.isUseKeyPrefix()) {
                config = config.disableKeyPrefix();
            } else {
                if (redisProperties.getKeyPrefix() != null) {
                    config = config.prefixCacheNameWith(redisProperties.getKeyPrefix());
                }
            }
        }
        return config;
    }

    @Bean(name = "jackson2JsonRedisSerializer")
    public Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer(){
        //解决查询缓存转换异常的问题
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.WRAPPER_ARRAY);
        return new Jackson2JsonRedisSerializer<>(om, Object.class);
    }
}

