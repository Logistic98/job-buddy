package com.jobbuddy.backend.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.jobbuddy.backend.modules.*.mapper")
public class MybatisPlusConfig {
}
