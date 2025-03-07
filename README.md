# redis-redisson-cache
#### 通过redisson设置读写锁，保证强一致性
本项目是对于spring redis cache切面和注解的改写。

本项目通过添加redisson分布式锁，实现redis缓存的读锁和写锁，从而保证数据的强一致性。

本项目添加了TTL有效期功能，通过接卸cacheName的时间片段（如：items#PT5M 中的PT5M），实现基于ISO8601的时间TTL的时间格式，从而获取TTL时长。

本项目引入Spel语法包，获取缓存对象的固定位置参数（如商品对象item的第一个参数id，可以使用#arg0，来获取该id），用于拼接redis缓存的key（如：items:1）
#### LockedCacheEvict切点及其切面
此部分是对原有CacheEvict的改写。
当更新数据库前，获取写锁。当更新数据库完成，并且删除数据库后，释放写锁。
```java
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target(ElementType.METHOD)
public @interface LockedCacheEvict {
    //普通的操作说明
    @AliasFor("cacheName")
    String name() default "";

    @AliasFor("name")
    String cacheName() default "";

    //spel表达式的操作说明
    String key() default "";
}
```
```java
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
    @Pointcut("@annotation(com.atguigu.cloud.redis.annotation.LockedCacheEvict)")
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
        //获取自定义注解的值，是否使用el表达式
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
            //执行加入双删注解的改动数据库的业务 即controller中的方法业务
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

```
#### LockedCacheable切点和切面
此部分是对原有Cacheable的改写。
当读取数缓存时，获取读锁。如果同时正在更新数据库，写锁已上锁，获取读锁失败。读操作需要等待写锁释放后，才可执行。<br />多读的情况，可以利用互斥锁，防止缓存击穿。
```java
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
```
```java
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
    @Pointcut("@annotation(com.atguigu.cloud.redis.annotation.LockedCacheable)")
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

```
<a name="z6UIP"></a>

#### 基本用法
###### LockedCacheEvict
此处更新一个Item，需要通过写锁删除redis中的缓存，从而保证get时的数据是最新的。

key代表从joinpoint获得的第一个参数“ItemEsDTO itemDTO”的id属性值。

此时redis中需要被删除的key为cacheName + ":" + itemDTO.id，如：items:1。
```java
@LockedCacheEvict(cacheName  = "items", key = "#arg0.id")
public ItemEsDTO update(ItemEsDTO itemDTO) {
    //此处省略
}
```
###### LockedCacheable
此处需要获得一个redis的缓存。

首先根据切割符“#”切割cacheName，获得：

name = items

ttl = 5 min

在根据key值，获得itemId

最终将name和itemId拼接，获得redis缓存的key，如：items:1。
```java
@LockedCacheable(cacheName  = "items#PT5M", key = "#arg0", sync = true)
public ItemEsDTO getItemById(Long itemId) {
    Item item = itemRepository.findById(itemId).orElse(null);
    if (item != null) {
        return itemUtil.createItemEsDTO(item);
    } else {
        throw new BadRequestException("该记录不存在");
    }
}
```
