package com.codingshuttle.youtube.hospitalManagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication bootstraps component scanning (finds every @Component/@Service/@Repository/
// @Controller/@Configuration under this package), auto-configuration (reacts to what's on the classpath -
// e.g. spring-boot-starter-data-jpa on the classpath is what causes Spring Boot to auto-configure the
// DataSource, EntityManagerFactory, and JpaTransactionManager beans used throughout this project), and
// marks this class itself as a @Configuration root.
@SpringBootApplication
public class HospitalManagementApplication {

    public static void main(String[] args) {
        // SpringApplication.run() creates the ApplicationContext, triggers the bean lifecycle
        // (instantiate -> populate dependencies -> BeanPostProcessors -> InitializingBean/@PostConstruct),
        // and starts the embedded servlet container - see src/MASTER_NOTES.md for the full startup sequence.
        SpringApplication.run(HospitalManagementApplication.class, args);
    }

}
