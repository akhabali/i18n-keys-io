package org.talend.components.i18n;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import lombok.Data;

@Data
public class Configuration {

    private final static String DATE_PATTERN = "dd/MM/yyyy";

    private final Locale locale = Locale.getDefault();

    private final TimeZone timeZone = TimeZone.getDefault();

    private final DateFormat dateFormat = new SimpleDateFormat(DATE_PATTERN, locale);
}
