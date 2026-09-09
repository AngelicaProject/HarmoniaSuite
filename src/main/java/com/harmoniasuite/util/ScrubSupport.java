package com.harmoniasuite.util;

import java.util.regex.Pattern;

public final class ScrubSupport {

    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(://)[^\\s/@:]+:[^\\s/@]*@");
    private static final Pattern HOME_USER = Pattern.compile(
            "(?i)([A-Za-z]:[\\\\/]Users[\\\\/]|/Users/|/home/)[^\\\\/\"']+(?=[\\\\/\"']|$)");

    private ScrubSupport() {
    }

    public static String scrub(String value) {
        if (value == null) {
            return null;
        }
        String masked = URL_CREDENTIALS.matcher(value).replaceAll("$1***@");
        return HOME_USER.matcher(masked).replaceAll("$1***");
    }
}
