package io.github.noonecanhearme.apimonitor.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用火焰图生成的注解
 * 用于标记需要生成火焰图的Controller方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableFlameGraph {

    /**
     * 火焰图采样时长（毫秒）
     * 默认使用配置文件中的值
     */
    int samplingDuration() default -1;

    /**
     * 火焰图文件名前缀
     */
    String fileNamePrefix() default "";

    /**
     * 是否包含详细的类和方法信息
     */
    boolean includeDetails() default true;
}