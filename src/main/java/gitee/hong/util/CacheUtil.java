package gitee.hong.util;

import cn.hutool.core.bean.BeanUtil;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.beans.BeanCopier;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class CacheUtil {
    private static Map<String, Map<Long, Object>> cache = Maps.newConcurrentMap();
    private static Map<String, Method> redisMethod = Maps.newConcurrentMap();
    public static boolean openCache = false;
    public static boolean remoteCache = true;
    public static int remoteCacheTimeoutAddSeconds = 60;
    public static String cachePrefix = "CacheUtil:";

    public static Object redisTemplate = null;
    private static Object opsForValue = null;
    private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(5, 20,
            5L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(200000));

    /**
     * 获取redisTemplate的方法
     *
     * @param methodName
     * @return
     * @throws Exception
     */
    private static Method getRedisMethod(String methodName) throws Exception {
        if (redisMethod.get(methodName) != null) {
            return redisMethod.get(methodName);
        }
        Method method = opsForValue.getClass().getMethod("set", new Class[]{Object.class, Object.class, long.class, TimeUnit.class});
        if (methodName.equals("get")) {
            method = opsForValue.getClass().getMethod("get", new Class[]{Object.class});
        }
        if (methodName.equals("delete")) {
            method = redisTemplate.getClass().getMethod("delete", new Class[]{Object.class});
        }
        method.setAccessible(true);
        redisMethod.put(methodName, method);
        return method;
    }

    /**
     * 初始化
     */
    private static  void init() {
        try {
            if (redisTemplate != null && opsForValue == null) {
                redisTemplate.getClass().getMethod("opsForValue");
                Method d = redisTemplate.getClass().getMethod("opsForValue");
                opsForValue = d.invoke(redisTemplate);
            }
        } catch (Exception e) {
            e.getMessage();
        }

    }

    /**
     * 缓存到远程服务器
     *
     * @param key
     * @param valueMap
     * @param sec
     */
    public static void saveRemoteCache(String key,Map<Long, Object> valueMap, int sec) throws Exception {
//        try {
//            String lockKey = key+"lock";
//            boolean isSuccess = redisTemplate.opsForValue().setIfAbsent(lockKey, value);
//            redisTemplate.expire(key, 10L, TimeUnit.SECONDS);
//            if(!isSuccess){
//                return;
//            }
//        }catch (Exception e){
//
//        }
        synchronized (key.intern()) {
            Object object = getCache(key);
            if (object != null) {
                log.info("拿到缓存，不需要在写入" + key);
                return;
            }
            if (sec <= 0) {
                return;
            }
//            Map<Long, Object> valueMap = Maps.newHashMap();
//            valueMap.put(DateUtil.offsetSecond(new Date(), sec).getTime(), value);
//            valueMap.put(System.currentTimeMillis()+(sec*1000), value);

            sec = sec + remoteCacheTimeoutAddSeconds;
            log.info("远程缓存:key{},过期时间:{}秒", key, sec);
//            redisTemplate.opsForValue().set(key,valueMap,sec, TimeUnit.SECONDS);
            getRedisMethod("set").invoke(opsForValue, key, valueMap, sec, TimeUnit.SECONDS);
        }
    }

    /**
     * 缓存保存
     *
     * @param key   保存的key
     * @param value 报错的值
     * @param sec   保留多少秒
     */
    public static void saveCache(String key, Object value, int sec) {
        try {
            if (!openCache) {
                return;
            }
            key = generateKey(key);
            log.info("远程缓存" + remoteCache);
            Map<Long, Object> valueMap = Maps.newHashMap();
            valueMap.put(System.currentTimeMillis()+(sec*1000), value);

            if (remoteCache) {
                saveRemoteCache(key, valueMap, sec);
                return;
            }
            int maxRecode = 900;
            if (cache.size() >= maxRecode) {
                log.info("缓存大于" + maxRecode + "条，启动清除过期数据。");
                cleanTimeOutCache();
                if (cache.size() >= maxRecode) {
                    log.info("缓存大于" + maxRecode + "条，不在保存数据避免JVM不够。");
                    return;
                }
            }
            synchronized (key.intern()) {
                Object object = getCache(key);
                if (object != null) {
                    log.info("拿到缓存，不需要在写入" + key);
                    return;
                }
                if (sec <= 0) {
                    return;
                }
                if (cache.get(key) != null) {
                    return;
                }
                cache.put(key, valueMap);
                cache.remove(key + "lock");
            }
        } catch (Exception e) {
            log.info("保存缓存异常" + e.getMessage(), e);
        }
    }

    /**
     * 缓存
     */
    private static void cleanTimeOutCache() {
        try {
            Set<String> keyset = cache.keySet();
            for (String key : keyset) {
                try {
                    Map<Long, Object> valueMap = cache.get(key);
                    if (cache.get(key) == null || valueMap.keySet().iterator().next() < System.currentTimeMillis()) {
                        cache.remove(key);
                    }
                } catch (Exception e) {

                }
            }
        } catch (Exception e) {

        }

    }

    /**
     * 获取过期的缓存
     * @param key
     * @return
     */
    public static Object getOldData(String key) {
        try {
            log.info("缓存开关:{}", openCache);
            if (!openCache) {
                return null;
            }
            Map<Long, Object> valueMap = getData(key);

            if(valueMap == null){
                return  null;
            }
            long cacheTime = Long.parseLong(valueMap.keySet().iterator().next()+"");
            if (cacheTime < System.currentTimeMillis()) {
                Object obj = valueMap.get(valueMap.keySet().iterator().next());
                return getReturnData(obj);
            }
        } catch (Exception e) {
            log.info("获取旧缓存异常" + e.getMessage(), e);
        }
        return null;

    }

    private static Map<Long, Object> getData(String key) throws Exception {
        key = generateKey(key);
        Map<Long, Object> valueMap = cache.get(key);
        if (valueMap != null) {
            return valueMap;
        }
        if (opsForValue != null) {
//            valueMap = (Map<Long, Object>) redisTemplate.opsForValue().get(key);
            valueMap = (Map<Long, Object>) getRedisMethod("get").invoke(opsForValue, key);
            if (valueMap != null) {
                log.info("拿到远程缓存数据:{}", key);
            }
        }
        return valueMap;
    }

    /**
     * 获取缓存
     *
     * @param key
     */
    public static Object getCache(String key) {
        try {
            log.info("缓存开关:{}", openCache);
            if (!openCache) {
                return null;
            }
            init();
            key = generateKey(key);
            if (getOldData( key) !=null) {
                cache.remove(key);

                if (opsForValue != null) {
                    getRedisMethod("delete").invoke(redisTemplate, key);
                }
                log.info("缓存过期删除" + key);
                return null;
            }
            Map<Long, Object> valueMap = getData(key);
            if (valueMap == null) {
                return null;
            }
            Object obj = valueMap.get(valueMap.keySet().iterator().next());
            return getReturnData(obj);

        } catch (Exception e) {
            log.info("获取缓存异常" + e.getMessage(), e);
        }
        return null;
    }

    private static String generateKey(String key) {
        key = key.startsWith(cachePrefix) ? key : cachePrefix + key;
        return key;
    }

//    public static void main(String[] args) throws Exception {
////        Map<Long, Object> valueMap = Maps.newHashMap();
////        valueMap.put(DateUtil.offsetSecond(new Date(), 1).getTime(), null);
//
//        ;
////        long ss = System.currentTimeMillis()+(6*1000);
////        System.out.println(DateUtil.offsetSecond(new Date(), 6).getTime()+":"+ss);
//
//        List<ActivityInfoAndMerchantListDTO> test = Lists.newLinkedList();
//
//        Set<ActivityInfoAndMerchantListDTO> testSet = Sets.newLinkedHashSet();
//
//        ActivityInfoAndMerchantListDTO[] test2 = new ActivityInfoAndMerchantListDTO[10199];
//
//        Map<String, MerchantStoreInfoDTO> merchantStoreInfoMap = new HashMap<>();
//
//        for (int i = 0; i < 110; i++) {
//            MerchantStoreInfoDTO n = new MerchantStoreInfoDTO();
//            n.setStoreNo("s" + i);
//            n.setSalesman("s" + i);
//            n.setStoreName("s" + i);
//            merchantStoreInfoMap.put("s" + i, n);
//        }
//
//        for (int i = 0; i < 10199; i++) {
//            ActivityInfoAndMerchantListDTO a = new ActivityInfoAndMerchantListDTO();
//            a.setActivityNo("aaaa" + i);
//            a.setMerchantAllocationRatio(11d);
//            a.setPlatformAllocationRatio(22d);
//            a.setActivityDescCb("dasdasd");
//            a.setMerchantStoreInfoMap(merchantStoreInfoMap);
//            test2[i] = a;
//            test.add(a);
//            testSet.add(a);
//        }
//        long start = System.currentTimeMillis();
//        Object O = getReturnData(test2);
//        System.out.println((System.currentTimeMillis() - start));
//        System.out.println("=================");
//        O = getReturnData("111");
//        System.out.println("=================");
//        O = getReturnData( test2[0]);
//    }


    /**
     * 对象复制
     * @param oldwObj
     * @return new Object
     * @throws Exception
     */
    public static Object beanCopy(Object oldwObj ) throws Exception {
        Object newObj = Class.forName(oldwObj.getClass().getName()).newInstance();
        try {
            final BeanCopier beanCopier = BeanCopier.create(oldwObj.getClass(), oldwObj.getClass(), false);
            beanCopier.copy(oldwObj,newObj,null);
        }catch (Exception e){
            System.out.println(e.getCause());
        }catch (LinkageError error){
            System.out.println(error.getCause());
        }
//        boolean d = BeanUtil.isEmpty(newObj);
//        boolean d1 =  BeanUtil.isEmpty(oldwObj);
        if(!newObj.equals(oldwObj)){
            log.info("beanCopier复制对象失败，改用hutoo对象复制:{}",oldwObj.getClass());
            BeanUtil.copyProperties(oldwObj, newObj);
        }
        return newObj;
    }

    /**
     * 组装返回信息
     *
     * @param obj
     * @return
     */
    private static Object getReturnData(Object obj) throws Exception {
        if (obj == null) {
            return null;
        }
        long start = System.currentTimeMillis();
        if (obj instanceof Collection || obj.getClass().isArray()) {
            boolean isArray = false;
            //如果是数组[]先转换成Collection集合
            if (obj.getClass().isArray()) {
                Object[] array = (Object[]) obj;
                obj = Arrays.stream(array).collect(Collectors.toList());
                isArray = true;
            }
            System.out.println((System.currentTimeMillis() - start));
            System.out.println("判断数组");
            if (((Collection) obj).iterator().hasNext()) {
                Collection collection = (Collection) obj;
                Map<String, Object> newDataMap = new ConcurrentHashMap<>();
                //是否使用线程池
                boolean usePool = collection.size() > 900 ? true : false;
                log.info("是否使用多线程：" + usePool);
                System.out.println("是否使用多线程：" + usePool);
                for (Object data : collection) {
//                    final BeanCopier beanCopier = BeanCopier.create(data.getClass(), data.getClass(), false);
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!usePool) {
                                    return;
                                }
//                                Thread.sleep(1000);
//                                Object newObj = Class.forName(data.getClass().getName()).newInstance();
                                Object newObj =  beanCopy( data  );
//                                BeanUtil.copyProperties(data, newObj);
//                                beanCopier.copy(data,newObj,null);
                                newDataMap.put(data.hashCode() + "", newObj);


                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            }
                        }
                    });
                    if (!usePool) {
//                        Object newObj = Class.forName(data.getClass().getName()).newInstance();
//                        BeanUtil.copyProperties(data, newObj);
                        Object newObj = beanCopy( data  );
                        newDataMap.put(data.hashCode() + "", newObj);
                    }
                }
                //1.5S内获取不到缓存就宣布失败
                int checkTime = 0;
                while (newDataMap.size() != collection.size() && usePool) {
                    if (checkTime >= 150) {
                        log.info("1.5S内获取不到缓存就宣布失败");
                        System.out.println("1.5S内获取不到缓存就宣布失败");
                        return null;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (Exception E) {

                    } finally {
                        checkTime = checkTime + 1;
                    }
//                    System.out.println("等待装配");
                }

//                pool.shutdown();
//                pool.awaitTermination(5, TimeUnit.SECONDS);
                System.out.println((System.currentTimeMillis() - start));
                System.out.println("装配map");
//                for (String key : newDataMap.keySet()) {
//                    for (Object data : collection) {
//                        if (new String(data.hashCode() + "").equals(key)) {
//                            collection.remove(data);
//                            collection.add(newDataMap.get(key));
//                            break;
//                        }
//                    }
//                }

                //重新组装数组
                Object returnData[] = collection.toArray();
                collection.clear();
                for (int i = 0; i < returnData.length; i++) {
                    String key = new String(returnData[i].hashCode() + "");
                    Object newData = newDataMap.get(key);
                    collection.add(newData);
                    returnData[i]=newData;
                }
                System.out.println((System.currentTimeMillis() - start));
                System.out.println("结束");
                //如果是数组[]把集合转行成数组返回
                if (isArray) {
                    return returnData;
                }
                return collection;
            } else {
                return null;
            }
        }
        //如果是基础类型直接返回
        if (obj.getClass().getName().startsWith("java.lang")) {
            return obj;
        }
//        Object newObj = Class.forName(obj.getClass().getName()).newInstance();
//        final BeanCopier beanCopier = BeanCopier.create(obj.getClass(), obj.getClass(), false);
//        beanCopier.copy(obj, newObj,null);
//        BeanUtil.copyProperties(obj, newObj);
        //普通对象就复制返回
        Object newObj = beanCopy( obj  );
        return newObj;
    }


}
