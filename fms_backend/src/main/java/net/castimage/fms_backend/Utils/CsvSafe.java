package net.castimage.fms_backend.Utils;

/**
 * CSV 单元格公式注入防护：以 = + - @ 或制表/回车开头的文本前置单引号，
 * 使 Excel/WPS 按文本而非公式解析；数字、日期、UUID 等非文本原样返回。
 */
public final class CsvSafe {

    private CsvSafe() {
    }

    /** 返回可安全交给 CSVPrinter 的值，null 保持 null（输出空单元格）。 */
    public static Object cell(Object value) {
        if (value instanceof String s && !s.isEmpty()
                && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return value;
    }
}
