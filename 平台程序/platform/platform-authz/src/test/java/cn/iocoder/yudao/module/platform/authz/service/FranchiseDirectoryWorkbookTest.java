package cn.iocoder.yudao.module.platform.authz.service;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FranchiseDirectoryWorkbookTest {
    private final FranchiseDirectoryWorkbook parser = new FranchiseDirectoryWorkbook();

    @Test
    void horizontalBlocksPreserveCellsAndBlankNumberedRowsWithoutInventingRecords() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("示例资料");
            sheet.createRow(0).createCell(0).setCellValue("测试品牌甲");
            sheet.getRow(0).createCell(5).setCellValue("测试品牌乙");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 5, 7));
            var header = sheet.createRow(1);
            for (int start : List.of(0, 5)) {
                header.createCell(start).setCellValue("系列");
                header.createCell(start + 1).setCellValue("省份");
                header.createCell(start + 2).setCellValue("品牌联盟区域");
            }
            var first = sheet.createRow(2);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("测试省");
            first.createCell(2).setCellValue("测试市甲县、乙县");
            first.createCell(5).setCellValue(1);
            first.createCell(6).setCellValue("其他省");
            first.createCell(7).setCellValue("其他市");
            var second = sheet.createRow(3);
            second.createCell(0).setCellValue(2);
            second.createCell(5).setCellValue(2);
            second.createCell(7).setCellValue("待核实开发区");
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 1, 1));
            workbook.createSheet("空工作表");
            bytes = bytes(workbook);
        }
        var rows = parser.parse(bytes);
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(FranchiseDirectoryWorkbook.Row::sourceKey).doesNotHaveDuplicates();
        assertThat(rows).extracting(FranchiseDirectoryWorkbook.Row::sourceCell).containsExactly("C3", "C4", "H3", "H4");
        assertThat(rows.get(0).originalRegionText()).isEqualTo("测试市甲县、乙县");
        assertThat(rows.get(0).regionCodes()).isEmpty();
        assertThat(rows.get(1).province()).isEqualTo("测试省");
        assertThat(rows.get(1).issue()).contains("地区");
        assertThat(rows.get(3).province()).isEmpty(); // No fill-down beyond an actual merge.
        assertThat(rows).allMatch(row -> row.recordType().equals("COVERAGE"));
        assertThat(parser.parse(bytes)).isEqualTo(rows);
    }

    @Test
    void standardRowsKeepExplicitKeysAndMultipleRegionCodesAndDefaultToCoverage() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("导入");
            var header = sheet.createRow(0);
            var headings = List.of("来源编号", "品牌", "名称", "省", "市", "地区原文", "地区代码", "记录类型");
            for (int col = 0; col < headings.size(); col++) header.createCell(col).setCellValue(headings.get(col));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("external-001");
            row.createCell(1).setCellValue("测试品牌");
            row.createCell(5).setCellValue("甲县、乙县");
            row.createCell(6).setCellValue("110101、110102,110101");
            bytes = bytes(workbook);
        }
        var row = parser.parse(bytes).get(0);
        assertThat(row.sourceKey()).isEqualTo("external-001");
        assertThat(row.sourceRow()).isEqualTo(2);
        assertThat(row.sourceCell()).isEqualTo("F2");
        assertThat(row.regionCodes()).containsExactly("110101", "110102");
        assertThat(row.recordType()).isEqualTo("COVERAGE");
        assertThat(row.issue()).isEmpty();
    }

    @Test
    void acceptsDocumentedHeaderAliasesAndRequiresRealNameForFranchiseRecords() throws Exception {
        for(String regionHeading:List.of("原始地区","地区原文","地区","县区","区县")) {
            byte[] bytes;
            try(var workbook=new XSSFWorkbook()) {
                var sheet=workbook.createSheet("登记");
                var header=sheet.createRow(0);
                var headings=List.of("加盟商编号","品牌名称","加盟商名称","省","市",regionHeading,"行政区划代码","记录类型");
                for(int col=0;col<headings.size();col++)header.createCell(col).setCellValue(headings.get(col));
                var row=sheet.createRow(1);
                row.createCell(0).setCellValue("source-001");
                row.createCell(1).setCellValue("测试品牌");
                row.createCell(5).setCellValue("测试县");
                row.createCell(6).setCellValue("110101＋110102");
                row.createCell(7).setCellValue("FRANCHISE");
                bytes=bytes(workbook);
            }
            var row=parser.parse(bytes).get(0);
            assertThat(row.sourceKey()).isEqualTo("source-001");
            assertThat(row.originalRegionText()).isEqualTo("测试县");
            assertThat(row.regionCodes()).containsExactly("110101","110102");
            assertThat(row.issue()).contains("真实加盟商名称");
        }
    }

    @Test
    void rejectFormulaWithoutReadingItsCachedResult() throws Exception {
        byte[] bytes;
        try (var workbook = standardWorkbook(1)) {
            workbook.getSheetAt(0).getRow(1).getCell(1).setCellFormula("1+1");
            bytes = bytes(workbook);
        }
        assertThatThrownBy(() -> parser.parse(bytes)).hasMessageContaining("公式");
    }

    @Test
    void rejectOversizedWorkbookAndMoreThanTwoThousandRecords() throws Exception {
        assertThatThrownBy(() -> parser.parse(new byte[5 * 1024 * 1024 + 1])).hasMessageContaining("5MB");
        byte[] bytes;
        try (var workbook = standardWorkbook(2001)) { bytes = bytes(workbook); }
        assertThatThrownBy(() -> parser.parse(bytes)).hasMessageContaining("2000");
    }

    @Test
    void rejectMacroPartsAndExternalRelationshipsBeforeOpeningWorkbook() throws Exception {
        byte[] original;
        try (var workbook = standardWorkbook(1)) { original = bytes(workbook); }
        byte[] macro = appendZipEntry(original, "xl/vbaProject.bin", new byte[]{1});
        byte[] external = appendZipEntry(original, "xl/_rels/custom.xml.rels",
                ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId99\" Type=\"externalLink\" Target=\"https://invalid.example\" TargetMode=\"External\"/>"
                        + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> parser.parse(macro)).hasMessageContaining("宏");
        assertThatThrownBy(() -> parser.parse(external)).hasMessageContaining("外部");
    }

    @Test
    void rejectNonemptyUnknownSheetInsteadOfSilentlyDroppingIt() throws Exception {
        byte[] bytes;
        try (var workbook = standardWorkbook(1)) {
            workbook.createSheet("无法识别").createRow(0).createCell(0).setCellValue("待整理资料");
            bytes = bytes(workbook);
        }
        assertThatThrownBy(() -> parser.parse(bytes)).hasMessageContaining("表头无法识别");
    }

    @Test
    void skipsOnlyNamedInstructionsAfterLookingForValidDataHeaders() throws Exception {
        byte[] bytes;
        try(var workbook=standardWorkbook(1)) {
            workbook.createSheet("填写说明").createRow(0).createCell(0).setCellValue("请按标准行政区填写资料");
            workbook.createSheet("导入说明").createRow(0).createCell(0).setCellValue("不含门店身份的资料请选择区域类型");
            bytes=bytes(workbook);
        }
        assertThat(parser.parse(bytes)).hasSize(1);
        try(var workbook=standardWorkbook(1)) {
            workbook.setSheetName(0,"填写说明");
            bytes=bytes(workbook);
        }
        var rows=parser.parse(bytes);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).sourceSheet()).isEqualTo("填写说明");
    }

    @Test
    void recognizesOnlyExplicitRequiredAndOptionalHeaderMarkers() throws Exception {
        byte[] bytes;
        try(var workbook=new XSSFWorkbook()) {
            var sheet=workbook.createSheet("登记");
            var header=sheet.createRow(0);
            var headings=List.of("品牌（必填）","省份*","城市（选填）","县区＊","来源编号 (可选)");
            for(int col=0;col<headings.size();col++)header.createCell(col).setCellValue(headings.get(col));
            var row=sheet.createRow(1);
            var values=List.of("测试品牌","测试省","测试市","测试县","marked-001");
            for(int col=0;col<values.size();col++)row.createCell(col).setCellValue(values.get(col));
            bytes=bytes(workbook);
        }
        var row=parser.parse(bytes).get(0);
        assertThat(row.brand()).isEqualTo("测试品牌");
        assertThat(row.province()).isEqualTo("测试省");
        assertThat(row.city()).isEqualTo("测试市");
        assertThat(row.originalRegionText()).isEqualTo("测试县");
        assertThat(row.sourceKey()).isEqualTo("marked-001");
        assertThat(row.issue()).isEmpty();
    }

    private static XSSFWorkbook standardWorkbook(int count) {
        var workbook = new XSSFWorkbook();
        var sheet = workbook.createSheet("导入");
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("品牌");
        header.createCell(1).setCellValue("地区原文");
        for (int index = 1; index <= count; index++) {
            var row = sheet.createRow(index);
            row.createCell(0).setCellValue("测试品牌");
            row.createCell(1).setCellValue("测试地区" + index);
        }
        return workbook;
    }

    private static byte[] bytes(Workbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    private static byte[] appendZipEntry(byte[] input, String name, byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output); var original = new ZipInputStream(new ByteArrayInputStream(input))) {
            for (ZipEntry entry; (entry = original.getNextEntry()) != null;) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                original.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
