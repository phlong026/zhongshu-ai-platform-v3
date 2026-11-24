package cn.iocoder.yudao.module.platform.authz.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads source rows only. Region resolution and import writes belong to the directory service. */
public final class FranchiseDirectoryWorkbook {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 40 * 1024 * 1024;
    private static final int MAX_RECORDS = 2000;
    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("来源编号", "sourceKey"), Map.entry("加盟商编号", "sourceKey"),
            Map.entry("品牌", "brand"), Map.entry("所属品牌", "brand"), Map.entry("品牌名称", "brand"),
            Map.entry("名称", "name"), Map.entry("加盟商名称", "name"),
            Map.entry("省", "province"), Map.entry("省份", "province"),
            Map.entry("市", "city"), Map.entry("城市", "city"),
            Map.entry("地区原文", "region"), Map.entry("原始地区", "region"), Map.entry("地区", "region"),
            Map.entry("区县", "region"), Map.entry("县区", "region"),
            Map.entry("地区代码", "codes"), Map.entry("区县代码", "codes"), Map.entry("行政区划代码", "codes"),
            Map.entry("记录类型", "type"));

    public record Row(String sourceKey, int sourceRow, String sourceSheet, String sourceCell,
                      String brand, String name, String province, String city,
                      String originalRegionText, String recordType, List<String> regionCodes,
                      String issue) { }

    private record StandardHeader(int row, Map<String, Integer> columns) { }
    private record BrandBlock(int headerRow, int regionColumn, int provinceColumn,
                              int serialColumn, String brand) { }

    public List<Row> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw invalid("请选择非空XLSX文件");
        if (bytes.length > MAX_BYTES) throw invalid("文件不能超过5MB");
        validatePackage(bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            validateCells(workbook);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var result = new ArrayList<Row>();
            var formatter = new DataFormatter(Locale.ROOT);
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (empty(sheet, formatter)) continue;
                StandardHeader standard = standardHeader(sheet, formatter);
                if (standard != null) {
                    parseStandard(sheet, sheetIndex, standard, digest, formatter, result);
                    continue;
                }
                List<BrandBlock> blocks = brandBlocks(sheet, formatter);
                if (blocks.isEmpty()) {
                    if (Set.of("填写说明", "导入说明").contains(sheet.getSheetName().strip())) continue;
                    throw invalid("工作表“" + sheet.getSheetName() + "”表头无法识别");
                }
                for (BrandBlock block : blocks) {
                    for (org.apache.poi.ss.usermodel.Row source : sheet) {
                        if (source.getRowNum() <= block.headerRow()) continue;
                        String region = value(sheet, source.getRowNum(), block.regionColumn(), formatter);
                        String serial = value(sheet, source.getRowNum(), block.serialColumn(), formatter);
                        if (region.isBlank() && serial.isBlank()) continue;
                        String province = mergedValue(sheet, source.getRowNum(), block.provinceColumn(), formatter);
                        add(result, row(sheet, sheetIndex, source.getRowNum(), block.regionColumn(), digest,
                                "", block.brand(), "", province, "", region, "COVERAGE", ""));
                    }
                }
            }
            if (result.isEmpty()) throw invalid("文件中没有可导入的资料行");
            return List.copyOf(result);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("无法读取XLSX文件，请检查文件是否损坏或已加密");
        }
    }

    private static void parseStandard(Sheet sheet, int sheetIndex, StandardHeader header, String digest,
                                      DataFormatter formatter, List<Row> result) {
        Map<String, Integer> columns = header.columns();
        int sourceColumn = columns.getOrDefault("region", columns.getOrDefault("codes", columns.get("brand")));
        for (org.apache.poi.ss.usermodel.Row source : sheet) {
            if (source.getRowNum() <= header.row()) continue;
            var fields = new HashMap<String, String>();
            columns.forEach((key, col) -> fields.put(key, value(sheet, source.getRowNum(), col, formatter)));
            if (fields.values().stream().allMatch(String::isBlank)) continue;
            add(result, row(sheet, sheetIndex, source.getRowNum(), sourceColumn, digest,
                    fields.getOrDefault("sourceKey", ""), fields.getOrDefault("brand", ""),
                    fields.getOrDefault("name", ""), fields.getOrDefault("province", ""),
                    fields.getOrDefault("city", ""), fields.getOrDefault("region", ""),
                    fields.getOrDefault("type", ""), fields.getOrDefault("codes", "")));
        }
    }

    private static Row row(Sheet sheet, int sheetIndex, int rowIndex, int column, String digest,
                           String sourceKey, String brand, String name, String province, String city,
                           String region, String type, String codesText) {
        String sourceCell = new CellReference(rowIndex, column).formatAsString();
        if (sourceKey.isBlank()) sourceKey = "xlsx:" + digest + ":s" + (sheetIndex + 1) + ":" + sourceCell;
        if (type.isBlank()) type = "COVERAGE";
        type = type.toUpperCase(Locale.ROOT);
        var codes = new LinkedHashSet<String>();
        for (String code : codesText.split("[\\s,，、;；/|+＋]+")) if (!code.isBlank()) codes.add(code);
        var issues = new ArrayList<String>();
        if (sourceKey.length() > 180) issues.add("来源编号最多180字");
        if (brand.isBlank()) issues.add("品牌不能为空");
        if (region.isBlank() && codes.isEmpty()) issues.add("地区原文或地区代码不能为空");
        if (!Set.of("COVERAGE", "FRANCHISE").contains(type)) issues.add("记录类型仅支持COVERAGE或FRANCHISE");
        if ("FRANCHISE".equals(type) && name.isBlank()) issues.add("加盟商档案须填写真实加盟商名称");
        if (codes.size() > 100 || codes.stream().anyMatch(code -> !code.matches("[0-9]{6}"))) {
            issues.add("地区代码须为6位数字，单行最多100个");
        }
        if (name.isBlank()) name = brand + (region.isBlank() ? "" : " · " + region);
        return new Row(sourceKey, rowIndex + 1, sheet.getSheetName(), sourceCell, brand, name,
                province, city, region, type, List.copyOf(codes), String.join("；", issues));
    }

    private static StandardHeader standardHeader(Sheet sheet, DataFormatter formatter) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getRowNum() > 30) break;
            var columns = new HashMap<String, Integer>();
            boolean repeated = false;
            for (Cell cell : row) {
                String key = HEADERS.get(headerName(cell, formatter));
                if (key != null && columns.putIfAbsent(key, cell.getColumnIndex()) != null) repeated = true;
            }
            if (columns.containsKey("brand") && (columns.containsKey("region") || columns.containsKey("codes"))) {
                if (repeated) throw invalid("标准表头不能包含重复字段");
                return new StandardHeader(row.getRowNum(), Map.copyOf(columns));
            }
        }
        return null;
    }

    private static List<BrandBlock> brandBlocks(Sheet sheet, DataFormatter formatter) {
        var blocks = new ArrayList<BrandBlock>();
        for (org.apache.poi.ss.usermodel.Row header : sheet) {
            if (header.getRowNum() == 0) continue;
            if (header.getRowNum() > 10) break;
            for (Cell cell : header) {
                if (!Set.of("品牌联盟区域", "公司").contains(headerName(cell, formatter))) continue;
                int col = cell.getColumnIndex();
                int first = col;
                String brand = mergedValue(sheet, header.getRowNum() - 1, col, formatter);
                for (CellRangeAddress merge : sheet.getMergedRegions()) {
                    if (merge.isInRange(header.getRowNum() - 1, col)) first = merge.getFirstColumn();
                }
                // A brand heading may be unmerged; stop at the preceding block's region column.
                if (brand.isBlank()) {
                    for (int candidate = col - 1; candidate >= 0; candidate--) {
                        if (Set.of("品牌联盟区域", "公司").contains(value(sheet, header.getRowNum(), candidate, formatter))) break;
                        String heading = value(sheet, header.getRowNum() - 1, candidate, formatter);
                        if (!heading.isBlank()) { brand = heading; first = candidate; break; }
                    }
                }
                int province = -1, serial = -1;
                for (int candidate = first; candidate < col; candidate++) {
                    String heading = value(sheet, header.getRowNum(), candidate, formatter);
                    if (Set.of("省份", "地区").contains(heading)) province = candidate;
                    if (Set.of("系列", "序号").contains(heading)) serial = candidate;
                }
                if (!brand.isBlank() && serial >= 0) blocks.add(new BrandBlock(header.getRowNum(), col, province, serial, brand));
            }
            if (!blocks.isEmpty()) return blocks;
        }
        return blocks;
    }

    private static String value(Sheet sheet, int row, int column, DataFormatter formatter) {
        if (column < 0 || sheet.getRow(row) == null) return "";
        return text(sheet.getRow(row).getCell(column), formatter);
    }

    private static String mergedValue(Sheet sheet, int row, int column, DataFormatter formatter) {
        if (column < 0) return "";
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(row, column)) return value(sheet, range.getFirstRow(), range.getFirstColumn(), formatter);
        }
        return value(sheet, row, column, formatter);
    }

    private static String text(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }

    private static String headerName(Cell cell, DataFormatter formatter) {
        return text(cell, formatter).replaceFirst("(?:\\s*(?:[＊*]|[（(](?:必填|选填|可选)[）)]))+$", "").strip();
    }

    private static boolean empty(Sheet sheet, DataFormatter formatter) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) for (Cell cell : row) {
            if (!text(cell, formatter).isBlank()) return false;
        }
        return true;
    }

    private static void add(List<Row> rows, Row row) {
        if (rows.size() >= MAX_RECORDS) throw invalid("单次最多导入2000条资料，请拆分文件");
        rows.add(row);
    }

    private static void validateCells(Workbook workbook) {
        if (workbook.getNumberOfSheets() > 32) throw invalid("工作表数量不能超过32个");
        int cells = 0;
        for (Sheet sheet : workbook) for (org.apache.poi.ss.usermodel.Row row : sheet) for (Cell cell : row) {
            if (++cells > 100000) throw invalid("工作簿内容过多，请仅保留待导入资料");
            if (cell.getCellType() == CellType.FORMULA) throw invalid("不支持公式，请复制为值后重新上传");
            if (cell.getCellType() == CellType.ERROR) throw invalid("工作簿含错误单元格，请修正后重新上传");
        }
    }

    private static void validatePackage(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw invalid("仅支持XLSX文件");
        int expanded = 0, entries = 0;
        boolean workbook = false;
        var names = new HashSet<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (++entries > 4000 || !names.add(name)) throw invalid("工作簿压缩结构异常");
                if (name.equals("xl/workbook.xml")) workbook = true;
                if (name.contains("vbaproject") || name.contains("macrosheet") || name.startsWith("xl/externallinks/")
                        || name.equals("xl/connections.xml") || name.startsWith("xl/querytables/")) {
                    throw invalid("不支持含宏或外部数据链接的工作簿");
                }
                boolean inspect = name.endsWith(".rels") || name.equals("[content_types].xml");
                var xml = inspect ? new ByteArrayOutputStream() : null;
                byte[] buffer = new byte[8192];
                for (int read; (read = zip.read(buffer)) >= 0;) {
                    expanded += read;
                    if (expanded > MAX_EXPANDED_BYTES) throw invalid("解压后内容过大，请仅保留待导入资料");
                    if (inspect) {
                        if (xml.size() + read > 1024 * 1024) throw invalid("工作簿关系数据异常");
                        xml.write(buffer, 0, read);
                    }
                }
                if (inspect) validateRelationships(xml.toByteArray());
            }
            if (!workbook) throw invalid("仅支持XLSX文件");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("工作簿压缩结构或外部链接不符合导入要求");
        }
    }

    private static void validateRelationships(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            var node = (org.w3c.dom.Element) nodes.item(index);
            if ("External".equalsIgnoreCase(node.getAttribute("TargetMode"))
                    || node.getAttribute("ContentType").toLowerCase(Locale.ROOT).contains("macroenabled")) {
                throw invalid("不支持含宏或外部数据链接的工作簿");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
