package com.alfatgbotik;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AlfaTgBotikApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(AlfaTgBotikApplication.class, args);
        if (context.getEnvironment().containsProperty("catalog.import-path")) {
            context.close();
        }
    }
}
