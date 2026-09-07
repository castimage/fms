package net.castimage.fms_backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FMS 后端启动类（包结构与 ccb_rcenter 同构，Mapper 接口统一扫描 net.castimage.fms_backend.Mapper）。
 * @EnableScheduling 驱动 NonceStore 与 AuthServiceImpl 内存表定时兜底清理。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("net.castimage.fms_backend.Mapper")
public class FmsBackendApplication {

    public static void main(String[] args) {
        // 配置类与过滤器链均在启动期装配完毕，
        SpringApplication.run(FmsBackendApplication.class, args);
    }

}
