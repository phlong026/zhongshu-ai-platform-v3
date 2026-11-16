package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StaffPostDepartmentStateTest {
    private final StaffPostServiceImpl service = new StaffPostServiceImpl();
    private final StaffPostMapper posts = mock(StaffPostMapper.class);
    private final OrgUnitMapper units = mock(OrgUnitMapper.class);
    private final PostTemplateMapper templates = mock(PostTemplateMapper.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "staffPostMapper", posts);
        ReflectionTestUtils.setField(service, "orgUnitMapper", units);
        ReflectionTestUtils.setField(service, "postTemplateMapper", templates);
        ReflectionTestUtils.setField(service, "systemDirectory", directory);
        when(posts.selectById(1L)).thenReturn(new StaffPostDO().setId(1L).setUserId(5L).setOrgId(10L)
                .setRoleCode("boss").setStatus("ACTIVE"));
        when(units.selectById(10L)).thenReturn(new OrgUnitDO().setDeptId(10L).setOrgId(10L)
                .setKind("ORG").setOrgType("store").setStatus("ACTIVE"));
        when(templates.selectById("boss")).thenReturn(new PostTemplateDO().setRoleCode("boss").setLead(false));
        when(directory.userUsable(5L)).thenReturn(true);
    }

    @Test
    void rejectsResidualPostWhenUnderlyingDepartmentIsDeletedOrDisabled() {
        when(directory.deptUnavailable(10L)).thenReturn(true);
        assertThat(service.findActivePost(5L, 1L)).isEmpty();
    }

    @Test
    void preservesValidPostWhenDepartmentIsUsable() {
        assertThat(service.findActivePost(5L, 1L)).isPresent();
    }

    @Test
    void rejectsClosedOrganizationEvenWhenDepartmentStillExists() {
        when(units.selectById(10L)).thenReturn(new OrgUnitDO().setDeptId(10L).setOrgId(10L)
                .setKind("ORG").setOrgType("store").setStatus("DISABLED"));
        assertThat(service.findActivePost(5L, 1L)).isEmpty();
    }
}
