package org.ofdrw.converter.utils;

/**
 * Unit conversions used by the isolated OFDRW AWT renderer.
 *
 * OFDRW 2.4.0's original CommonUtil combines these conversions with iText-returning methods.
 * Loading that class therefore requires iText even when its PDF exporter is not used. This
 * deliberately small, source-level compatibility adapter implements the real unit conversions
 * needed by this worker, allowing the unused AGPL/commercial exporters to be excluded entirely.
 * The original class is excluded from the worker JAR by Maven; no iText classes are substituted.
 * Reference: Apache-2.0 OFDRW 2.4.0 official source, converter/utils/CommonUtil.java.
 */
public final class CommonUtil {
    private CommonUtil() {}

    public static double dpiToPpm(int dpi) { return dpi / 25.4d; }
    public static double millimetersToPixel(double mm, double dpi) { return mm * dpi / 25.4d; }
    public static double pixelToMillimeters(double pixels, double dpi) { return pixels * 25.4d / dpi; }
    public static double converterDpi(double mm) { return millimetersToPixel(mm, 72); }
}
