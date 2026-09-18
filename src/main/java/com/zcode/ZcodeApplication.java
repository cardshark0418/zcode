package com.zcode;

import com.zcode.cli.InteractiveCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZcodeApplication {

    public static void main(String[] args) {
        if (isServe(args)) {
            SpringApplication app = new SpringApplication(ZcodeApplication.class);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            app.run(stripServe(args));
            return;
        }

        // Default: Claude-like interactive CLI (no Tomcat)
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ZcodeApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR",
                        "logging.level.com.zcode=INFO"
                )
                .run(args)) {
            ctx.getBean(InteractiveCli.class).start();
        }
    }

    private static boolean isServe(String[] args) {
        return args.length > 0 && "serve".equalsIgnoreCase(args[0]);
    }

    private static String[] stripServe(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }
}
