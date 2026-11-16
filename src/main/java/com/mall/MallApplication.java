package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商交易与秒杀系统启动类
 *
 * @author mall
 */
@SpringBootApplication
@MapperScan("com.mall.mapper")
@ConfigurationPropertiesScan("com.mall.security")
@EnableScheduling
@EnableAsync
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
