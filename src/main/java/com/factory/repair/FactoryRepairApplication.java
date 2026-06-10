package com.factory.repair;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.factory.repair.mapper")
@EnableScheduling
@EnableAsync
public class FactoryRepairApplication {

    public static void main(String[] args) {
        SpringApplication.run(FactoryRepairApplication.class, args);
    }
}
