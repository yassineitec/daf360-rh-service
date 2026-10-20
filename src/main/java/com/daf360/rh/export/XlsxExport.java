package com.daf360.rh.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * One-sheet Excel export: a header row and typed cells.
 *
 * The shared export module agreed for the leave screens (decision 2026-09-30): a domain only
 * flattens itself into headers + rows, this class does the rest. In-process with Apache POI —
 * a separate export service would add a deployable and ship whole datasets over HTTP for no
 * gain (pdf-service exists only because PDF needs a browser runtime).
 */
public final class XlsxExport {

    /** A list screen is paged at 10–50 rows; an export of the same filter is capped here. */
    public static final int MAX_ROWS = 10_000;

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private XlsxExport() {}

    public static ResponseEntity<byte[]> response(String fileName, String sheetName,
                                                  List<String> headers, List<List<Object>> rows) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(XLSX)
                .body(build(sheetName, headers, rows));
    }

    public static byte[] build(String sheetName, List<String> headers, List<List<Object>> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);
            CreationHelper helper = wb.getCreationHelper();

            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);

            CellStyle date = wb.createCellStyle();
            date.setDataFormat(helper.createDataFormat().getFormat("dd/mm/yyyy"));
            CellStyle time = wb.createCellStyle();
            time.setDataFormat(helper.createDataFormat().getFormat("hh:mm"));

            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(head);
            }

            int r = 1;
            for (List<Object> values : rows) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < values.size(); i++) {
                    Object v = values.get(i);
                    if (v == null) continue;
                    Cell c = row.createCell(i);
                    if (v instanceof Number n) {
                        c.setCellValue(n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue());
                    } else if (v instanceof LocalDate d) {
                        c.setCellValue(d);
                        c.setCellStyle(date);
                    } else if (v instanceof LocalTime t) {
                        c.setCellValue(t.toSecondOfDay() / 86_400d);
                        c.setCellStyle(time);
                    } else if (v instanceof Boolean b) {
                        c.setCellValue(b ? "Oui" : "Non");
                    } else {
                        c.setCellValue(v.toString());
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < headers.size(); i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Excel export failed", e);
        }
    }

    /** French or English label of a request state — the same four the screens show. */
    public static String stateLabel(String etat, String lang) {
        boolean fr = !"en".equalsIgnoreCase(lang);
        Map<String, String[]> labels = Map.of(
                "EN_ATTENTE", new String[] {"En attente", "Pending"},
                "VALIDE",     new String[] {"Validée", "Approved"},
                "REFUSE",     new String[] {"Refusée", "Rejected"},
                "ARCHIVE",    new String[] {"Annulée", "Cancelled"});
        String[] l = labels.get(etat);
        return l == null ? etat : l[fr ? 0 : 1];
    }
}
