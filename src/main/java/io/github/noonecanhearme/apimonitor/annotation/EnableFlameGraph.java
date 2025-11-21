package io.github.noonecanhearme.apimonitor.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable flame graph generation
 * Used to mark Controller methods that need flame graph generation
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableFlameGraph {

    /**
     * Flame graph sampling duration (milliseconds)
     * Use value from configuration file by default
     */
    int samplingDuration() default -1;

    /**
     * Flame graph file name prefix
     */
    String fileNamePrefix() default "";

    /**
     * Whether to include detailed class and method information
     */
    boolean includeDetails() default true;
}