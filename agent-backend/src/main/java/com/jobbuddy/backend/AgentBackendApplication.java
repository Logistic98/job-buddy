package com.jobbuddy.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动 Agent 后端应用。
 */
@EnableScheduling
@SpringBootApplication
public class AgentBackendApplication {
  private static final Logger log = LoggerFactory.getLogger(AgentBackendApplication.class);

  /**
   * 启动命令行入口。
   *
   * @param args 调用参数
   */
  public static void main(String[] args) {
    ConfigurableApplicationContext context =
        SpringApplication.run(AgentBackendApplication.class, args);
    Environment env = context.getEnvironment();
    String port = env.getProperty("server.port", "8080");
    log.info("Swagger在线接口文档地址：http://localhost:{}/doc.html", port);
    log.info("Swagger导入Postman地址：http://localhost:{}/v3/api-docs", port);
  }
}
