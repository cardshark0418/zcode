package com.zcode.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * When developing zcode itself: prefer on-disk {@code src/main/resources/static} over the jar so
 * Web UI edits apply on browser refresh (Cursor-like dogfooding).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StaticDevConfig implements WebMvcConfigurer {

    private final ZcodeHome zcodeHome;

    public StaticDevConfig(ZcodeHome zcodeHome) {
        this.zcodeHome = zcodeHome;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path disk = zcodeHome.workspace().resolve("src").resolve("main").resolve("resources").resolve("static");
        if (!Files.isDirectory(disk)) {
            return;
        }
        String loc = disk.toAbsolutePath().normalize().toUri().toString();
        if (!loc.endsWith("/")) {
            loc = loc + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(loc, "classpath:/static/")
                .setCacheControl(CacheControl.noStore().mustRevalidate())
                .resourceChain(false);
        // Keep API controllers authoritative; this only fills static GETs.
    }
}
