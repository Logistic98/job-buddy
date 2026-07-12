package com.jobbuddy.backend.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 注册各业务模块的 MyBatis Mapper 接口，由 Spring 统一创建代理实例。 */
@Configuration
@MapperScan("com.jobbuddy.backend.modules.*.mapper")
public class MybatisPlusConfig {
}
