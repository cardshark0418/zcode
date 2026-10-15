package com.zcode.web;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * After {@code zcode serve} is ready, open the Web UI in the default browser.
 *
 * <p>Disable with {@code -Dzcode.serve.open-browser=false} or {@code ZCODE_SERVE_OPEN_BROWSER=false}.
 */
@Component
@ConditionalOnWebApplication
public class ServeBrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(ServeBrowserOpener.class);

    private final Environment environment;

    public ServeBrowserOpener(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!openBrowserEnabled()) {
            return;
        }
        String url = uiUrl();
        Thread t = new Thread(() -> open(url), "zcode-open-browser");
        t.setDaemon(true);
        t.start();
    }

    private boolean openBrowserEnabled() {
        String prop = System.getProperty("zcode.serve.open-browser");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("ZCODE_SERVE_OPEN_BROWSER");
        }
        if (prop != null && !prop.isBlank()) {
            return !"false".equalsIgnoreCase(prop.trim()) && !"0".equals(prop.trim());
        }
        // Only auto-open when launched via `zcode serve` (see ZcodeApplication).
        return "true".equalsIgnoreCase(System.getProperty("zcode.serve", ""));
    }

    private String uiUrl() {
        String port = environment.getProperty("local.server.port");
        if (port == null || port.isBlank()) {
            port = environment.getProperty("server.port", "8080");
        }
        return "http://127.0.0.1:" + port.trim() + "/";
    }

    private static void open(String url) {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                log.info("Opened browser: {}", url);
                return;
            }
        } catch (Exception e) {
            log.debug("Desktop.browse failed: {}", e.getMessage());
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.redirectErrorStream(true);
            pb.start();
            log.info("Opened browser via OS helper: {}", url);
        } catch (Exception e) {
            log.warn("Could not open browser for {} ({})", url, e.getMessage());
        }
    }
}
