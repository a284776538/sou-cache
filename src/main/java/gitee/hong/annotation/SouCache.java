package gitee.hong.annotation;

import java.lang.annotation.*;

/**
 * 国际化枚举转化
 * 注意事项
 * 1、对应的枚举类必须设置 setMessage()方法
 * 2、入参的language必传
 *
 * @author guoquanqin
 * @version 1.0, 2022/1/10 10:45
 */
@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SouCache {

    /**
     * 缓存秒数
     * @return
     */
    int sce() default  0;

    /**
     * 缓存关键字
     * @return
     */
    String key() default "";
    /**
     * 0-100 过期缓存刷新几率。
     * 10.就会有10%拿到过期缓存的人去刷新缓存数据
     * 0代表不允许返回过期缓存
     * @return
     */
    int refresh() default 10;
}
