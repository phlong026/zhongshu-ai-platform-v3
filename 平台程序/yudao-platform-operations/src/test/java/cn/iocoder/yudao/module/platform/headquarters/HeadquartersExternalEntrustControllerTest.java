package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Request;
import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort.Resource;
import cn.iocoder.yudao.module.platform.headquarters.controller.admin.HeadquartersDocumentController;
import cn.iocoder.yudao.module.platform.headquarters.controller.admin.HeadquartersExternalEntrustController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HeadquartersExternalEntrustControllerTest {
    private HeadquartersReadService reads;
    private HeadquartersDocumentAccessPort documents;
    private AssetAccessPort assets;
    private OperateLogCommonApi audit;
    private MockMvc mvc;
    @BeforeEach void setup() {
        reads=mock(HeadquartersReadService.class);documents=mock(HeadquartersDocumentAccessPort.class);
        assets=mock(AssetAccessPort.class);audit=mock(OperateLogCommonApi.class);
        mvc=MockMvcBuilders.standaloneSetup(new HeadquartersExternalEntrustController(reads,assets,documents,audit),
                new HeadquartersDocumentController(assets)).build();
    }
    @Test void versionRoutesKeepParsedJsonStringAssetIdsAndBoundedPageParameters() throws Exception {
        when(reads.externalVersions(anyLong(),anyString(),anyInt(),anyInt())).thenReturn(new HeadquartersReadModel.Page(
                List.of(Map.of("version",2,"data",Map.of("text","实际需求"),"attachments",List.of(Map.of("assetId","9007199254740999","title","设计需求")))),3,2,1,true));
        for(String kind:List.of("requests","results")) {
            mvc.perform(get("/platform/headquarters/customers/101/external-entrust/"+kind).param("pageNo","2").param("pageSize","1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.readOnly").value(true))
                    .andExpect(jsonPath("$.data.list[0].data.text").value("实际需求"))
                    .andExpect(jsonPath("$.data.list[0].attachments[0].assetId").value("9007199254740999"));
            verify(reads).externalVersions(101,kind,2,1);
        }
        mvc.perform(post("/platform/headquarters/customers/101/external-entrust/requests").contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(assets,documents,audit);
    }
    @Test void bytesUseTheExactVersionGrantAuditAndUncachedAttachmentResponse() throws Exception {
        long asset=9007199254740999L;byte[] bytes="%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        var request=new Request(Resource.CUSTOMER,101,"EXTERNAL_RESULT:3",asset);
        when(documents.requireReadable(request)).thenReturn(new HeadquartersDocumentAccessPort.Scope(11,22,33,44,55,asset,Set.of("BUSINESS_DOCUMENT")));
        when(assets.readHeadquartersDocument(request)).thenReturn(new AssetAccessPort.ReferenceContent(bytes,"application/pdf","a".repeat(64)));
        mvc.perform(get("/platform/headquarters/customers/101/external-entrust/results/3/assets/"+asset+"/content"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"external-document.pdf\""))
                .andExpect(header().doesNotExist("Location"));
        verify(assets).readHeadquartersDocument(request);verify(documents,times(2)).requireReadable(request);
        verify(audit).createOperateLog(argThat(event->event.getBizId()==101L&&event.getUserId()==22L&&event.getSubType().equals("results")));
    }
    @Test void workflowDownloadAliasCannotBypassTheAuditedExternalVersionRoute() {
        var ordinary=new HeadquartersDocumentController(assets);
        assertThatThrownBy(()->ordinary.customer(101,"EXTERNAL_REQUEST:1",9)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(assets,documents,audit);
    }
}
