package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostMapper;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.STAFF_MOBILE_AMBIGUOUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YudaoSystemDirectoryTest {

    private static final String MOBILE = "13800001111";

    @Mock
    private AdminUserMapper adminUserMapper;
    @Mock
    private StaffPostMapper staffPostMapper;
    @Mock
    private cn.iocoder.yudao.module.system.service.user.AdminUserService adminUserService;

    private YudaoSystemDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new YudaoSystemDirectory();
        ReflectionTestUtils.setField(directory, "adminUserMapper", adminUserMapper);
        ReflectionTestUtils.setField(directory, "staffPostMapper", staffPostMapper);
        ReflectionTestUtils.setField(directory, "adminUserService", adminUserService);
    }

    @Test
    void userIdByMobileReusesOnlyAccountWithActiveAppointmentWhenHistoryHasDuplicates() {
        when(adminUserMapper.selectListByMobile(MOBILE)).thenReturn(List.of(user(10L), user(20L)));
        when(staffPostMapper.selectActiveByUsers(List.of(10L, 20L)))
                .thenReturn(List.of(activePost(20L)));

        assertThat(directory.userIdByMobile(MOBILE)).isEqualTo(20L);
    }

    @Test
    void userIdByMobileRejectsMultipleActiveEmployeesInsteadOfPickingFirst() {
        when(adminUserMapper.selectListByMobile(MOBILE)).thenReturn(List.of(user(10L), user(20L)));
        when(staffPostMapper.selectActiveByUsers(List.of(10L, 20L)))
                .thenReturn(List.of(activePost(10L), activePost(20L)));

        assertThatThrownBy(() -> directory.userIdByMobile(MOBILE))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(STAFF_MOBILE_AMBIGUOUS.getCode()));
    }

    @Test
    void userIdByMobileRejectsMultipleUnassignedAccountsInsteadOfPickingFirst() {
        when(adminUserMapper.selectListByMobile(MOBILE)).thenReturn(List.of(user(10L), user(20L)));
        when(staffPostMapper.selectActiveByUsers(List.of(10L, 20L))).thenReturn(List.of());

        assertThatThrownBy(() -> directory.userIdByMobile(MOBILE))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(STAFF_MOBILE_AMBIGUOUS.getCode()));
    }

    @Test
    void userIdByMobileKeepsZeroAndSingleCandidateSemantics() {
        when(adminUserMapper.selectListByMobile(MOBILE)).thenReturn(List.of());
        assertThat(directory.userIdByMobile(MOBILE)).isNull();

        when(adminUserMapper.selectListByMobile(MOBILE)).thenReturn(List.of(user(10L)));
        assertThat(directory.userIdByMobile(MOBILE)).isEqualTo(10L);
    }

    @Test
    void initializedPasswordUsesSameSystemUserAndStrongBcryptHash() {
        when(adminUserService.createUser(org.mockito.ArgumentMatchers.any())).thenReturn(10L);
        String initial = InitialAccountPassword.generate();
        assertThat(directory.createUserWithPassword("李忠", MOBILE, 100L, initial)).isEqualTo(10L);
        var captured = org.mockito.ArgumentCaptor.forClass(AdminUserDO.class);
        org.mockito.Mockito.verify(adminUserMapper).updateById(captured.capture());
        AdminUserDO updated = captured.getValue();
        assertThat(updated.getId()).isEqualTo(10L);
        assertThat(updated.getPassword()).startsWith("$2a$10$").isNotEqualTo(initial);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(initial, updated.getPassword())).isTrue();
    }

    @Test
    void statusChangeLocksUserBeforePostsWithoutAnExtraStatusWrite() {
        directory.setUserActive(10L, true);
        var order = org.mockito.Mockito.inOrder(adminUserMapper, staffPostMapper, adminUserService);
        order.verify(adminUserMapper).lockById(10L);
        order.verify(staffPostMapper).lockUserPostsForUpdate(10L);
        order.verify(adminUserService).updateUserStatus(10L, 0);
        order.verifyNoMoreInteractions();
    }

    private static AdminUserDO user(long id) {
        return AdminUserDO.builder().id(id).mobile(MOBILE).build();
    }

    private static StaffPostDO activePost(long userId) {
        return new StaffPostDO().setUserId(userId).setStatus(StaffPostDO.STATUS_ACTIVE);
    }

}
