package com.harmoniasuite;

import com.harmoniasuite.config.HarmoniaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@SpringBootApplication
@EnableConfigurationProperties(HarmoniaProperties.class)
public class HarmoniaSuiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarmoniaSuiteApplication.class, args);
    }
}
