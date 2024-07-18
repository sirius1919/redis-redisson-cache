package com.mcsirius.cloud.redis.aspect;


import cn.hutool.core.util.StrUtil;
import com.mcsirius.cloud.redis.annotation.LockedCacheEvict;
import com.mcsirius.cloud.redis.utils.SpelUtil;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;

@Aspect
@Component
public class LockedCacheEvictAspect {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    CacheProperties cacheProperties;

    /**
     * 切入点
     *切入点,基于注解实现的切入点  加上该注解的都是Aop切面的切入点
     *
     */
    @Pointcut("@annotation(com.mcsirius.cloud.redis.annotation.LockedCacheEvict)")
    public void pointCut(){

    }
    /**
     * 环绕通知
     * 环绕通知非常强大，可以决定目标方法是否执行，什么时候执行，执行时是否需要替换方法参数，执行完毕是否需要替换返回值。
     * 环绕通知第一个参数必须是org.aspectj.lang.ProceedingJoinPoint类型
     * @param proceedingJoinPoint
     */
    @Around("pointCut()")
    public Object aroundAdvice(ProceedingJoinPoint proceedingJoinPoint){
        System.out.println("----------- 环绕通知 -----------");
        Signature signature1 = proceedingJoinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature)signature1;
        Method targetMethod = methodSignature.getMethod();//方法对象
        LockedCacheEvict annotation = targetMethod.getAnnotation(LockedCacheEvict.class);//反射得到自定义注解的方法对象
        String name = "";
        String key;
        //获取自定义注解的值，是否使用Spel表达式
        if (annotation != null) {
            if (StrUtil.isNotBlank(annotation.cacheName())) {
                name = annotation.cacheName();
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

        }
        System.out.println("环绕通知的目标方法名：" + proceedingJoinPoint.getSignature().getName()+",keys="+name);

        //获取写锁
        RReadWriteLock rwLock = redissonClient.getReadWriteLock(name+":rw");
        RLock writeLock = rwLock.writeLock();
        Object proceed = null;
        try {
            writeLock.lock();
            try {
                proceed = proceedingJoinPoint.proceed();
                System.out.println(proceedingJoinPoint.getSignature().getName()+"方法执行结束");
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }

            //删除redis缓存中对应的key
            Set<String> keys = stringRedisTemplate.keys(name);//確切刪除
            stringRedisTemplate.delete(keys);//删除redis的key值
            System.out.println("删除redis缓存中对应的key："+name);
        } finally {
            writeLock.unlock();
        }
        return proceed;//返回业务代码的值
    }
}
