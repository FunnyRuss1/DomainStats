package ru.jointworld.domainstats;

import java.net.IDN;
import java.util.Locale;

public final class DomainNames {
    public static final String UNKNOWN = "unknown-host";

    private DomainNames() { }

    public static String normalize(String input) {
        if (input == null) return UNKNOWN;
        // Forge may append handshake metadata after a NUL separator
        int separator = input.indexOf('\0');
        String host = (separator < 0 ? input : input.substring(0, separator)).strip();
        if (host.isEmpty()) return UNKNOWN;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("Некорректный IPv6 адрес");
            String suffix = host.substring(end + 1);
            if (!suffix.isEmpty() && !suffix.matches(":[0-9]{1,5}")) invalid();
            host = host.substring(1, end);
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') > 0) {
            String port = host.substring(host.indexOf(':') + 1);
            if (!port.matches("[0-9]{1,5}")) invalid();
            host = host.substring(0, host.indexOf(':'));
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) invalid();
        if (host.indexOf(':') >= 0) {
            if (!host.matches("[0-9a-fA-F:.]+")) invalid();
            return host.toLowerCase(Locale.ROOT);
        }
        String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.length() > 253) invalid();
        return ascii;
    }

    public static String fromConnection(String host) {
        try {
            return normalize(host);
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    private static void invalid() {
        throw new IllegalArgumentException("Укажи домен без ссылки и пути");
    }
}
