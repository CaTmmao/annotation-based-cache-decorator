package com.github.hcsp.annotation;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class CacheClassDecorator {
    public static class CacheInterceptor {
        public static class CacheMapHashCodeKey {
            Method method;
            Object thisObject;
            Object[] args;

            public CacheMapHashCodeKey(Method method, Object thisObject, Object[] args) {
                this.method = method;
                this.thisObject = thisObject;
                this.args = args;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                CacheMapHashCodeKey that = (CacheMapHashCodeKey) o;
                return Objects.equals(method, that.method) && Objects.equals(thisObject, that.thisObject) && Arrays.equals(args, that.args);
            }

            @Override
            public int hashCode() {
                int result = Objects.hash(method, thisObject);
                result = 31 * result + Arrays.hashCode(args);
                return result;
            }
        }

        public static class CacheMapValueObject {
            Object superCallResult;
            long cacheTime;

            public CacheMapValueObject(Object superCallResult, long cacheTime) {
                this.superCallResult = superCallResult;
                this.cacheTime = cacheTime;
            }
        }

        public static boolean ifNotExceedCacheAnnotationCacheSeconds(CacheMapValueObject cacheMapValueObject, Method method) {
            long currTime = System.currentTimeMillis();
            long cacheTime = cacheMapValueObject.cacheTime;
            long cacheAnnotationTime = method.getAnnotation(Cache.class).cacheSeconds() * 1000L;

            return !(currTime - cacheTime > cacheAnnotationTime);
        }

        public static void cacheMethodResultToMap(Object superCallResult, CacheMapHashCodeKey cacheMapHashCodeKey) {
            long cacheTime = System.currentTimeMillis();
            CacheMapValueObject cacheMapValueObject = new CacheMapValueObject(superCallResult, cacheTime);
            cacheMap.put(cacheMapHashCodeKey, cacheMapValueObject);
        }

        public static ConcurrentHashMap<CacheMapHashCodeKey, CacheMapValueObject> cacheMap = new ConcurrentHashMap<>();

        @RuntimeType
        public static Object cache(@SuperCall Callable<Object> superCall,
                                   @Origin Method method,
                                   @This Object thisObject,
                                   @AllArguments Object[] args) throws Exception {
            CacheMapHashCodeKey cacheMapHashCodeKey = new CacheMapHashCodeKey(method, thisObject, args);
            CacheMapValueObject cacheMapValueObject = cacheMap.get(cacheMapHashCodeKey);

            if (cacheMapValueObject != null) {
                if (ifNotExceedCacheAnnotationCacheSeconds(cacheMapValueObject, method)) {
                    return cacheMapValueObject.superCallResult;
                }
            }

            Object superCallResult = superCall.call();
            cacheMethodResultToMap(superCallResult, cacheMapHashCodeKey);
            return superCallResult;
        }
    }


    // 将传入的服务类Class进行增强
    // 使得返回一个具有如下功能的Class：
    // 如果某个方法标注了@Cache注解，则返回值能够被自动缓存注解所指定的时长
    // 这意味着，在短时间内调用同一个服务的同一个@Cache方法两次
    // 它实际上只被调用一次，第二次的结果直接从缓存中获取
    // 注意，缓存的实现需要是线程安全的
    @SuppressWarnings("unchecked")
    public static <T> Class<T> decorate(Class<T> klass) {
        return (Class<T>) new ByteBuddy()
                .subclass(klass)
                .method(ElementMatchers.isAnnotatedWith(Cache.class))
                .intercept(MethodDelegation.to(CacheInterceptor.class))
                .make()
                .load(DataService.class.getClassLoader())
                .getLoaded();
    }


    public static void main(String[] args) throws Exception {
        DataService dataService = decorate(DataService.class).getConstructor().newInstance();

        // 有缓存的查询：只有第一次执行了真正的查询操作，第二次从缓存中获取
        System.out.println(dataService.queryData(1));
        Thread.sleep(1 * 1000);
        System.out.println(dataService.queryData(1));
        Thread.sleep(3 * 1000);
        System.out.println(dataService.queryData(1));

        // 无缓存的查询：两次都执行了真正的查询操作
        System.out.println(dataService.queryDataWithoutCache(1));
        Thread.sleep(1 * 1000);
        System.out.println(dataService.queryDataWithoutCache(1));
    }
}
