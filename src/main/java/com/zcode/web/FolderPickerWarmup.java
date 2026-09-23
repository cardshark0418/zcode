package com.zcode.web;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** Preloads AWT/Swing so the first workspace folder pick is instant. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FolderPickerWarmup implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        FolderPicker.warmUp();
    }
}
