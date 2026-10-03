package com.tradepass.module.trade.service.ledger;

import com.tradepass.framework.common.exception.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Excel layout follows the supplied B:I project ledger template. */
final class ProjectLedgerWorkbook {
    private static final List<String> FIELDS = List.of("contractAmount", "amount", "paymentAmount", "unpaidAmount", "invoiceAmount", "unbilledAmount");

    private ProjectLedgerWorkbook() { }

    @SuppressWarnings("unchecked")
    static byte[] generate(Map<String, Object> detail) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("项目账套台账明细");
            sheet.setDisplayGridlines(false);
            sheet.setColumnWidth(0, 3 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 30 * 256);
            for (int col = 3; col <= 8; col++) sheet.setColumnWidth(col, 18 * 256);
            CellStyle textStyle = style(workbook, false, false);
            CellStyle numberStyle = style(workbook, false, false);
            numberStyle.setAlignment(HorizontalAlignment.RIGHT);
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00"));
            CellStyle dateStyle = style(workbook, false, false);
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy/m/d"));
            CellStyle heading = style(workbook, true, true);
            CellStyle totalStyle = style(workbook, true, true);
            totalStyle.setAlignment(HorizontalAlignment.RIGHT);
            totalStyle.setDataFormat(numberStyle.getDataFormat());
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setFontName("微软雅黑");
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 18);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            Row title = sheet.createRow(2);
            title.setHeightInPoints(36);
            cell(title, 1, titleStyle).setCellValue(String.valueOf(detail.get("title")));
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 8));
            Row reportingDate = sheet.createRow(3);
            cell(reportingDate, 6, textStyle).setCellValue("日期");
            cell(reportingDate, 7, dateStyle).setCellValue(((LocalDate) detail.get("asOfDate")).atStartOfDay());
            sheet.addMergedRegion(new CellRangeAddress(3, 3, 7, 8));
            int rowIndex = 5;
            List<Map<String, Object>> groups = (List<Map<String, Object>>) detail.get("groups");
            for (Map<String, Object> group : groups) {
                Row company = sheet.createRow(rowIndex++);
                company.setHeightInPoints(30);
                for (int col = 1; col <= 8; col++) cell(company, col, heading);
                company.getCell(1).setCellValue(group.get("counterpartyName") + "（" + group.get("directionText") + "）");
                sheet.addMergedRegion(new CellRangeAddress(company.getRowNum(), company.getRowNum(), 1, 8));
                Row header = sheet.createRow(rowIndex++);
                header.setHeightInPoints(28);
                for (int col = 1; col <= 8; col++) cell(header, col, heading).setCellValue(ProjectLedgerDetail.COLUMNS.get(col - 1));
                int firstDataRow = rowIndex + 1;
                for (Map<String, Object> source : ProjectLedgerDetail.rows(group)) {
                    Row row = sheet.createRow(rowIndex++);
                    row.setHeightInPoints(42);
                    Cell date = cell(row, 1, dateStyle);
                    if (source.get("date") instanceof LocalDate value) date.setCellValue(value.atStartOfDay());
                    String documentNo = String.valueOf(source.get("documentNo"));
                    cell(row, 2, textStyle).setCellValue(source.get("name") + (documentNo.isBlank() ? "" : "\n" + documentNo));
                    for (int index = 0; index < FIELDS.size(); index++) {
                        Cell value = cell(row, index + 3, numberStyle);
                        if (source.get(FIELDS.get(index)) instanceof BigDecimal amount) value.setCellValue(amount.doubleValue());
                    }
                    if (!"CONTRACT".equals(source.get("sourceType"))) {
                        int current = rowIndex;
                        row.getCell(6).setCellFormula("SUM(E" + firstDataRow + ":E" + current
                                + ")-SUM(F" + firstDataRow + ":F" + current + ")");
                        row.getCell(8).setCellFormula("SUM(F" + firstDataRow + ":F" + current
                                + ")-SUM(H" + firstDataRow + ":H" + current + ")");
                    }
                }
                Row total = sheet.createRow(rowIndex++);
                total.setHeightInPoints(28);
                for (int col = 1; col <= 8; col++) cell(total, col, totalStyle);
                total.getCell(3).setCellValue("合计");
                for (int col : new int[]{4, 5, 7}) {
                    String letter = Character.toString((char) ('A' + col));
                    total.getCell(col).setCellFormula("SUM(" + letter + firstDataRow + ":" + letter + (rowIndex - 1) + ")");
                }
                total.getCell(6).setCellFormula("E" + rowIndex + "-F" + rowIndex);
                total.getCell(8).setCellFormula("F" + rowIndex + "-H" + rowIndex);
                rowIndex++;
            }
            if (groups.isEmpty()) cell(sheet.createRow(rowIndex++), 1, textStyle).setCellValue("暂无已生效合同，请先划分合同");
            sheet.createFreezePane(3, 7);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            workbook.setPrintArea(0, 1, 8, 2, rowIndex - 1);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException("项目台账 Excel 生成失败");
        }
    }

    private static Cell cell(Row row, int index, CellStyle style) {
        Cell cell = row.createCell(index);
        cell.setCellStyle(style);
        return cell;
    }

    private static CellStyle style(Workbook workbook, boolean bold, boolean fill) {
        Font font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 11);
        font.setBold(bold);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        if (fill) {
            style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return style;
    }
}
