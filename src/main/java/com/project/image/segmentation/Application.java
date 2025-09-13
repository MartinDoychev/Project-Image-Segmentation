package com.project.image.segmentation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Spring Boot application. The purpose of this class is ONLY to bootstrap
 * the app — it must NOT contain business logic.
 *
 * @SpringBootApplication triggers component scanning, auto-configuration, and allows us to
 * declare extra configuration in this package tree.
 */
    @SpringBootApplication
    public class Application {
        public static void main(String[] args) {
            // Delegates to Spring Boot to start embedded Tomcat and initialize beans.
        SpringApplication.run(Application.class, args);
    }
}