package com.mall.metrics;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /actuator/info 输出实例标识（实例序号/容器主机名/JDK 版本），
 * 用于验证 Nginx 负载均衡是否在双实例间轮询。
 *
 * @author mall
 */
@Component
public class InstanceInfoContributor implements InfoContributor {

    private final Environment environment;

    public InstanceInfoContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("index", environment.getProperty("MALL_INSTANCE_INDEX", "local"));
        instance.put("hostname", environment.getProperty("HOSTNAME", "unknown"));
        instance.put("javaVersion", System.getProperty("java.version"));
        builder.withDetail("instance", instance);
    }
}
