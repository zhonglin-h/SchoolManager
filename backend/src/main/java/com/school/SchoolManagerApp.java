package com.school;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SchoolManagerApp {
    public static void main(String[] args) {
        SpringApplication.run(SchoolManagerApp.class, args);
    }
}
