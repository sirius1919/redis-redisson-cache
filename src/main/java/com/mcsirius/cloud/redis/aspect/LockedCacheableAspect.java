package com.mcsirius.cloud.redis.aspect;


import cn.hutool.core.util.StrUtil;
import com.mcsirius.cloud.redis.annotation.LockedCacheable;
import com.mcsirius.cloud.redis.utils.SpelUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
public class LockedCacheableAspect {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    CacheProperties cacheProperties;

    /**
     * 切入点
     *切入点,基于注解实现的切入点  加上该注解的都是Aop切面的切入点
     *
     */
    @Pointcut("@annotation(com.mcsirius.cloud.redis.annotation.LockedCacheable)")
    public void pointCut(){

    }
    /**
     * 环绕通知
     * 环绕通知非常强大，可以决定目标方法是否执行，什么时候执行，执行时是否需要替换方法参数，执行完毕是否需要替换返回值。
     * 环绕通知第一个参数必须是org.aspectj.lang.ProceedingJoinPoint类型
     * @param proceedingJoinPoint
     */
    @Around("pointCut()")
    public Object aroundAdvice(ProceedingJoinPoint proceedingJoinPoint) {
        System.out.println("----------- 环绕通知 -----------");
        Signature signature1 = proceedingJoinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature)signature1;
        Method targetMethod = methodSignature.getMethod();//方法对象
        LockedCacheable annotation = targetMethod.getAnnotation(LockedCacheable.class);//反射得到自定义注解的方法对象
        String name = "";
        String key;
        Duration duration = null;
        boolean sync = false;
        //获取自定义注解的值，是否使用el表达式
        if (annotation != null) {
            if (StrUtil.isNotBlank(annotation.cacheName())) {
                name = annotation.cacheName();
            }
            String[] array = StringUtils.delimitedListToStringArray(name, "#");
            name = array[0];
            if (array.length > 1) {
                try {
                    duration = Duration.parse(array[1]);
                } catch (DateTimeParseException e) {
                    log.error("错误的 TTL 格式");
                    throw e;
                }
            }
            //注解上的描述
            if (StrUtil.isNotBlank(annotation.key())) {
                key = SpelUtil.generateKeyBySpEL(annotation.key(), proceedingJoinPoint);
                if (!Objects.equals(name, "")) {
                    name = name + ":";
                }
                name = name + key;
            }
            if (null != cacheProperties){
                CacheProperties.Redis redisProperties = cacheProperties.getRedis();
                if (redisProperties.isUseKeyPrefix() && redisProperties.getKeyPrefix() != null) {
                    name = redisProperties.getKeyPrefix()+name;
                }
            }
            sync = annotation.sync();
        }
        System.out.println("环绕通知的目标方法名：" + proceedingJoinPoint.getSignature().getName()+",keys="+name);

        Object proceed;
        //获取写锁
        RReadWriteLock rwLock = redissonClient.getReadWriteLock(name+":rw");
        RLock readLock = rwLock.readLock();
        while(true) {
            try {
                boolean isReadLocked = readLock.tryLock(0, -1, TimeUnit.SECONDS);
                if (!isReadLocked) {
                    Thread.sleep(500);
                    continue;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (sync) {
                while (true) {
                    proceed = getObjectFromRedis(name);
                    if (proceed == null) {
                        RLock rLock = redissonClient.getLock(name + ":sync");
                        boolean isLocked = false;
                        try {
                            isLocked = !rLock.tryLock(0, -1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        System.out.println(isLocked);
                        if (isLocked) {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            try {
                                proceed = proceedingJoinPoint.proceed();
                                System.out.println(proceedingJoinPoint.getSignature().getName() + "方法执行结束");
                                redisTemplate.opsForValue().set(name, proceed);
                                if (duration != null) {
                                    redisTemplate.expire(name, duration);
                                }
                                System.out.println("写入了以下redis缓存：" + name);
                            } catch (Throwable e) {
                                throw new RuntimeException(e);
                            } finally {
                                // 释放锁
                                rLock.unlock();
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
            } else {
                proceed = getObjectFromRedis(name);
            }
            readLock.unlock();
            break;
        }
        return proceed;//返回业务代码的值
    }

    private Object getObjectFromRedis(String name) {
        Set<String> keys = redisTemplate.keys(name);
        if (keys != null && !keys.isEmpty()) {
            // 如果成功获取redis缓存，则直接返回缓存
            Object proceed = redisTemplate.opsForValue().get(name);
            System.out.println("读取了以下redis缓存：" + name);
            return proceed;
        } else {
            return null;
        }
    }
}
