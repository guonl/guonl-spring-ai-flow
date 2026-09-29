package com.guonl;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.guonl.flow.db.mapper")
public class GuonlSpringAiFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuonlSpringAiFlowApplication.class, args);
    }

}
