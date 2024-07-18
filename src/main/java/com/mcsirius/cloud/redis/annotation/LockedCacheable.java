package com.mcsirius.cloud.redis.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target(ElementType.METHOD)
public @interface LockedCacheable {
    //普通的操作说明
    @AliasFor("cacheName")
    String name() default "";

    @AliasFor("name")
    String cacheName() default "";

    //spel表达式的操作说明
    String key() default "";

    boolean sync() default false;
}
