package com.zcode.cli;

import com.zcode.permission.PermissionMode;
import com.zcode.permission.PermissionService;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Consumer;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

/**
 * JLine input with Claude-style mode footer + Shift+Tab to cycle.
 */
final class CliLineEditor implements AutoCloseable {

    private final Terminal terminal;
    private final LineReader reader;
    private final PermissionService permissionService;
    private final Consumer<PermissionMode> onModeCycled;
    private final Status status;

    private CliLineEditor(
            Terminal terminal,
            LineReader reader,
            PermissionService permissionService,
            Consumer<PermissionMode> onModeCycled) {
        this.terminal = terminal;
        this.reader = reader;
        this.permissionService = permissionService;
        this.onModeCycled = onModeCycled;
        this.status = Status.getStatus(terminal, true);
        if (status != null) {
            status.setBorder(false);
        }
        refreshStatus();
    }

    static CliLineEditor open(Charset charset, PermissionService permissionService, Consumer<PermissionMode> onModeCycled)
            throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(charset)
                .jansi(true)
                .build();
        if (terminal.getType() != null && terminal.getType().toLowerCase().contains("dumb")) {
            terminal.close();
            throw new IOException("dumb terminal");
        }

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.INSERT_TAB, true)
                .build();

        CliLineEditor editor = new CliLineEditor(terminal, reader, permissionService, onModeCycled);
        editor.bindCycleMode();
        return editor;
    }

    private void bindCycleMode() {
        final String widget = "zcode-cycle-permission-mode";
        reader.getWidgets().put(widget, () -> {
            PermissionMode next = permissionService.cycle();
            refreshStatus();
            if (onModeCycled != null) {
                onModeCycled.accept(next);
            }
            return true;
        });
        Binding ref = new Reference(widget);
        KeyMap<Binding> map = reader.getKeyMaps().get(LineReader.MAIN);
        // CSI Z — common Shift+Tab / back-tab
        map.bind(ref, "\u001b[Z");
        map.bind(ref, "\033[Z");
        try {
            String btab = KeyMap.key(terminal, InfoCmp.Capability.key_btab);
            if (btab != null && !btab.isEmpty()) {
                map.bind(ref, btab);
            }
        } catch (Exception ignored) {
            // capability missing on some Windows terms
        }
    }

    void refreshStatus() {
        if (status == null) {
            return;
        }
        PermissionMode mode = permissionService.mode();
        AttributedStyle style = AttributedStyle.DEFAULT.foreground(221); // warm yellow
        AttributedString line = new AttributedString(
                "▶ " + mode.statusLabel() + " (shift+tab to cycle)",
                style);
        status.update(List.of(line));
    }

    void hideStatus() {
        if (status != null) {
            status.hide();
        }
    }

    String readUserLine() throws IOException {
        refreshStatus();
        try {
            return reader.readLine(CuiStyle.ACCENT_BOLD + "❯" + CuiStyle.RESET + " ");
        } catch (UserInterruptException e) {
            return "";
        } catch (EndOfFileException e) {
            return null;
        }
    }

    String readAskLine(String prompt) throws IOException {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException e) {
            return "";
        } catch (EndOfFileException e) {
            return null;
        }
    }

    Terminal terminal() {
        return terminal;
    }

    @Override
    public void close() throws IOException {
        hideStatus();
        terminal.close();
    }
}
