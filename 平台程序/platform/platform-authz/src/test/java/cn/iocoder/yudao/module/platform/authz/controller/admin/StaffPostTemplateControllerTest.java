package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffPostTemplateControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StaffOrgAdminController controller = new StaffOrgAdminController();
        CurrentPost currentPost = () -> Optional.of(new PostSnapshot(11, 7, 100, "store", "boss", null, false, Set.of()));
        ReflectionTestUtils.setField(controller, "currentPost", currentPost);
        ReflectionTestUtils.setField(controller, "orgService", mock(OrgService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ownOrganizationCanReadEmptyPostTemplateList() throws Exception {
        mvc.perform(get("/platform/org/post-templates").param("orgId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void anotherOrganizationIsRejectedBeforeReadingItsTemplates() {
        assertThatThrownBy(() -> mvc.perform(get("/platform/org/post-templates").param("orgId", "999")))
                .hasRootCauseInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
}
