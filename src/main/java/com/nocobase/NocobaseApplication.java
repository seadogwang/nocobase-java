package com.nocobase;

import com.nocobase.config.NocobaseDataSourceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableConfigurationProperties(NocobaseDataSourceProperties.class)
@EnableTransactionManagement
public class NocobaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(NocobaseApplication.class, args);
    }
}
