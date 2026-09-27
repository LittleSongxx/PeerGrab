package com.peergrab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 组装根（composition root）：唯一知道所有层的地方。
 * 领域层与应用层都不认识 Spring Boot，装配在这里统一完成。
 */
@SpringBootApplication
@EnableScheduling
@EnableTransactionManagement
public class PeerGrabApplication {

    public static void main(String[] args) {
        SpringApplication.run(PeerGrabApplication.class, args);
    }

}
