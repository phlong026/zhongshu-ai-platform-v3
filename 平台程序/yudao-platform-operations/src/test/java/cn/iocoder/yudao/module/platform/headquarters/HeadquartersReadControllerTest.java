package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Request;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Resource;
import cn.iocoder.yudao.module.platform.headquarters.controller.admin.HeadquartersDocumentController;
import cn.iocoder.yudao.module.platform.headquarters.controller.admin.HeadquartersReadController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP shape/verb tests; actual role, tenant and post authorization is exercised by the PostgreSQL suite. */
class HeadquartersReadControllerTest {
    private HeadquartersReadService reads;
    private HeadquartersExportService exports;
    private AssetAccessPort assets;
    private MockMvc mvc;

    @BeforeEach void setup() {
        reads = mock(HeadquartersReadService.class); exports = mock(HeadquartersExportService.class); assets = mock(AssetAccessPort.class);
        mvc = MockMvcBuilders.standaloneSetup(new HeadquartersReadController(reads, exports), new HeadquartersDocumentController(assets)).build();
    }

    @Test void allPageRoutesBindTheSameExplicitBrandCompanyAndPaginationFilters() throws Exception {
        when(reads.page(anyString(), any())).thenReturn(new HeadquartersReadModel.Page(List.of(Map.of("id", "9007199254740999")), 7, 2, 10, true));
        for (String path : List.of("customers", "projects", "finance/receipts", "finance/payables", "finance/payments", "finance/refunds")) {
            mvc.perform(get("/platform/headquarters/" + path).param("brandId", "101").param("companyId", "202")
                    .param("pageNo", "2").param("pageSize", "10").param("keyword", "完整关键词")
                    .param("dateFrom", "2026-11-01").param("dateTo", "2026-11-25"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.readOnly").value(true))
                    .andExpect(jsonPath("$.data.total").value(7)).andExpect(jsonPath("$.data.list[0].id").value("9007199254740999"));
        }
        var filter = ArgumentCaptor.forClass(HeadquartersReadModel.Filter.class);
        verify(reads, times(6)).page(anyString(), filter.capture());
        assertThat(filter.getAllValues()).allSatisfy(value -> {
            assertThat(value.brandId()).isEqualTo(101); assertThat(value.companyId()).isEqualTo(202);
            assertThat(value.keyword()).isEqualTo("完整关键词"); assertThat(value.pageNo()).isEqualTo(2);
            assertThat(value.dateFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        });
        verify(reads).page(eq("customers"), any()); verify(reads).page(eq("projects"), any()); verify(reads).page(eq("refunds"), any());
    }

    @Test void headquartersBusinessRoutesExposeNoWriteHandlers() throws Exception {
        for (String path : List.of("customers/1", "projects/2", "customers/1/workflow", "finance/receipts/3", "companies/4/ledger")) {
            mvc.perform(post("/platform/headquarters/" + path).contentType("application/json").content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
        verifyNoInteractions(reads, exports, assets);
    }

    @Test void exportsReturnOnlyAuditedBytesAsAnUncachedDownload() throws Exception {
        byte[] csv = "\uFEFF品牌,公司\r\n品牌甲,门店甲\r\n".getBytes(StandardCharsets.UTF_8);
        when(exports.export(eq("customers"), any())).thenReturn(csv);
        mvc.perform(post("/platform/headquarters/exports").param("dataset", "customers").param("companyId", "44"))
                .andExpect(status().isOk()).andExpect(content().bytes(csv))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"headquarters-customers.csv\""));
        verify(exports).export(eq("customers"), argThat(filter -> Long.valueOf(44).equals(filter.companyId())));
    }

    @Test void documentEndpointsPassBusinessReferencesAndNeverReturnExternalUrls() throws Exception {
        byte[] bytes = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        when(assets.readHeadquartersDocument(any())).thenReturn(new AssetAccessPort.ReferenceContent(bytes, "application/pdf", "a".repeat(64)));
        mvc.perform(get("/platform/headquarters/customers/1/workflow/HOUSE_SELECTION/assets/9/content"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Location"));
        verify(assets).readHeadquartersDocument(new Request(Resource.CUSTOMER, 1, "HOUSE_SELECTION", 9));
        mvc.perform(get("/platform/headquarters/projects/2/documents/7/content")).andExpect(status().isOk());
        verify(assets).readHeadquartersDocument(new Request(Resource.PROJECT, 2, "DOCUMENT", 7));
        mvc.perform(get("/platform/headquarters/projects/2/assets/8/content").param("section", "SITE_RECORD")).andExpect(status().isOk());
        verify(assets).readHeadquartersDocument(new Request(Resource.PROJECT, 2, "SITE_RECORD", 8));
        mvc.perform(get("/platform/headquarters/finance/refunds/4/assets/10/content")).andExpect(status().isOk());
        verify(assets).readHeadquartersDocument(new Request(Resource.REFUND, 4, "VOUCHER", 10));
    }
}
