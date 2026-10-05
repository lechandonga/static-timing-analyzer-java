package com.github.lechandonga.statictiminganalyzerjava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        boolean cli = java.util.Arrays.stream(args)
                .anyMatch(arg -> arg.startsWith("--sta="));
        new SpringApplicationBuilder(Application.class)
                .web(cli
                        ? org.springframework.boot.WebApplicationType.NONE
                        : org.springframework.boot.WebApplicationType.SERVLET)
                .run(args);
    }
}
