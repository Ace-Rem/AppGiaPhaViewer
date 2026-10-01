package com.android.acerem.xemgp.data;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates the same deterministic member filename as the web Viewer/Editor. */
public final class ImageFilenameResolver {
    private static final Pattern ISO_YEAR = Pattern.compile("^(\\d{4})(?:[-/.].*)?$");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^\\d{1,2}[-/.]\\d{1,2}[-/.](\\d{4})$");

    private ImageFilenameResolver() {}

    public static String forMember(Member member) {
        if (member == null) return null;
        return forMember(member.fullName, member.birthDate);
    }

    public static String forMember(String fullName, String birthDate) {
        String name = fullName == null ? "" : fullName.trim();
        String year = birthYear(birthDate);
        if (name.isEmpty() || year.isEmpty()) return null;
        String normalized = stripMarks(name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isEmpty() ? null : normalized + year + ".webp";
    }

    public static String birthYear(String birthDate) {
        if (birthDate == null) return "";
        String value = birthDate.trim();
        Matcher iso = ISO_YEAR.matcher(value);
        if (iso.matches()) return iso.group(1);
        Matcher dayMonthYear = DAY_MONTH_YEAR.matcher(value);
        return dayMonthYear.matches() ? dayMonthYear.group(1) : "";
    }

    private static String stripMarks(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
    }
}
