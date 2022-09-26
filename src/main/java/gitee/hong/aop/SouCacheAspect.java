package gitee.hong.aop;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.google.common.collect.Maps;
import gitee.hong.annotation.SouCache;
import gitee.hong.service.SouRefreshCacheStrategy;
import gitee.hong.util.CacheUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 缓存插件
 */
@Aspect
@EnableAspectJAutoProxy
@Slf4j
public class SouCacheAspect implements ApplicationContextAware {
    @Value("${sou.cache.open:false}")
    private boolean cache;
    @Value("${sou.cache.remote:false}")
    private boolean remote;
    @Value("${sou.cache.remote.timeout.add.seconds:60}")
    private int  remoteCacheTimeoutAddSeconds;
    @Value("${spring.application.name:SouCache}")
    private  String cachePrefix;

    private SouRefreshCacheStrategy globalRefreshCacheStrategy;
    //缓存日志
    private  Map<String, Map<Boolean,Long>> cacheLog = Maps.newConcurrentMap();
    //缓存命中比例
    private  Map<String,BigDecimal> cacheRatio= Maps.newConcurrentMap();

    private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 5,
            5L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(200000));

    // 配置织入点
    @Pointcut("@annotation( gitee.hong.annotation.SouCache) ")
    public void pointcut() {

    }


    /**
     * 获得注解
     * @param joinPoint
     * @return
     */
    private SouCache getAnnotation(ProceedingJoinPoint joinPoint){
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method exmethod = methodSignature.getMethod();
        SouCache cache = exmethod.getDeclaringClass().getAnnotation(SouCache.class)==null? exmethod.getAnnotation(SouCache.class): exmethod.getDeclaringClass().getAnnotation(SouCache.class);
        return cache;
    }


    /**
     * 缓存key缩短
     * @param key
     * @return
     */
    private String shortenKey(String key){
        if(key.length()<=100){
            return key;
        }

        key=key.length()>100? key.substring(0,50)+key.intern().hashCode()+"leng:"+key.length():key;
        key = key.replace("{","").replace("}","");
        return key;
    }
    /**
     * 获取缓存的key
     * @param joinPoint
     * @return
     */
    private String getKey( ProceedingJoinPoint joinPoint){
        String key=null;
        try {
            SouCache cache = getAnnotation( joinPoint);
            key  =cache.key();
            Object[] args =  joinPoint.getArgs();
            StringBuilder subKey=new StringBuilder();
            if(args.length>0){
                for(Object arg:args){
                    String argkey="";
                    try {
                        argkey = shortenKey(JSONUtil.toJsonStr(arg));
                    }catch (Exception e){
                        argkey = arg+"";
                    }
                    subKey.append(argkey);
                }
            }
            key = key+subKey.toString();
//            log.info("自动缓存{},key:{}",cache,key);
//            log.info("自动缓存key:{}",key);
        }catch (Exception e){
            log.info("缓存异常，解析KEY"+e.getMessage(),e);
        }
        return key;
    }

    /**
     * 获取缓存
     * @param joinPoint
     * @return
     */
    private Object getCache(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean isOld = false;
        Object obj = null;
        String key = getKey(joinPoint);
        try {
            CacheUtil.cachePrefix = cachePrefix + ":cache:";
            CacheUtil.openCache = cache;
            CacheUtil.remoteCache = remote;
            CacheUtil.remoteCacheTimeoutAddSeconds = remoteCacheTimeoutAddSeconds;
            log.info("缓存状态开启状态：{},远程缓存开启状态:{}",cache,remote);
            Object old = CacheUtil.getOldData(key);
            if (old == null) {
                obj = CacheUtil.getCache(key);
                if (obj != null) {
                    log.info(key + "拿到缓存");
                }
            }
            if (old != null && obj == null) {
                obj = old;
                isOld = true;
                if (obj != null) {
                    log.info(key + "拿到旧数据");
                }
            }
        } catch (Exception e) {
            log.info("获取缓存异常:" + key, e);
        }
        int ran = RandomUtil.randomInt(0, 100);
        SouCache cache = getAnnotation(joinPoint);
        //如果有自定义的刷新缓存策略
        if (isOld && globalRefreshCacheStrategy != null) {
            try {
                Boolean lock = globalRefreshCacheStrategy.refreshPermission(key);
                log.info("获得刷新权限" + key);
                if(lock!=null){
                    ran = lock ? cache.refresh() - 1 : cache.refresh() + 1;
                }
            } catch (Exception e) {
                log.error("自定义刷新策略错误，使用默认刷新策略" + e.getMessage(), e);
            }
        }
        if (isOld && cache.refresh() > ran) {
            log.info(ran + "|" + cache.refresh() + "刷新数据" + key);
            return null;
        }
        return obj;
    }

    /**
     *
     */
    @Around("pointcut()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Object cacheData=getCache( joinPoint);
        if(cacheData!=null){
            log.info("缓存返回"+getKey(joinPoint));
            updateLog( joinPoint ,true);
            return cacheData;
        }
        log.info("非缓存返回"+getKey(joinPoint));
        updateLog( joinPoint ,false);
        return save(joinPoint);
    }

    /**
     * 保存数据
     * @param joinPoint
     * @returnk
     * @throws Throwable
     */
    private Object save(ProceedingJoinPoint joinPoint) throws Throwable {
        Object o = joinPoint.proceed();
        try {
            SouCache cache = getAnnotation(joinPoint);
            if (cache != null) {
                String key =  getKey(  joinPoint);
                int sec = cache.sce();
                if (key != null && key.length() > 2) {
                    CacheUtil.saveCache(key, o, sec);
                }
            }
            //避免原对象被后续流程修改数据，返回复制对象
            String key = getKey(joinPoint);
            Object o2 = CacheUtil.getCache(key);
            if(o2!=null){
                return o2;
            }
        }catch (Exception e){
            log.error("缓存保存失败"+e.getMessage(),e);
        }
        return o;
    }

    /**
     * 更新缓存命中日志
     * @param joinPoint
     * @param target
     */
    private  void updateLog( ProceedingJoinPoint joinPoint ,boolean target){
        pool.execute(new Runnable() {
            @Override
            public void run() {
                SouCache cache = getAnnotation( joinPoint);
                String key  =cache.key();
                synchronized (key.intern()){
                    Map<Boolean,Long> cacheLogDetail =   cacheLog.get(key)==null?new ConcurrentHashMap<>():cacheLog.get(key);
                    cacheLogDetail.put(target,cacheLogDetail.get(target)==null?1:cacheLogDetail.get(target)+1);
                    cacheLog.put(key,cacheLogDetail);
                    BigDecimal ratio =new BigDecimal("0");
                    try {
                        long igCache = cacheLogDetail.get(false)==null?0:cacheLogDetail.get(false);
                        long getCache = cacheLogDetail.get(true)==null?0:cacheLogDetail.get(true);
                        long all =igCache+getCache;
                        ratio = new BigDecimal(getCache+"").divide(new BigDecimal(all+""),2, 4);

                    }catch (Exception e){

                    }
                    cacheRatio.put(key ,ratio);
                }
            }
        });


    }

    /**
     * 5分钟打印异常缓存命中比例
     */
    private void disPlayLog() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                while (1 == 1) {
                    log.info("缓存比例日志:{}", cacheRatio);
                    ThreadUtil.sleep(60*5*1000);
                }
            }
        });
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        try {
            CacheUtil.redisTemplate = applicationContext.getBean("redisTemplate");
        }catch (Exception e){
            log.error(e.getMessage(),e);
        }
        try {
            this.globalRefreshCacheStrategy =  applicationContext.getBean(SouRefreshCacheStrategy.class);
        }catch (Exception e){
            log.error(e.getMessage(),e);
        }
        disPlayLog();
    }
}
