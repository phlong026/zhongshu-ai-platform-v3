package cn.iocoder.yudao.module.system.dal.mysql.user;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AdminUserMapper extends BaseMapperX<AdminUserDO> {

    /** 只锁定当前租户的用户行，不写状态或更新时间。 */
    @Select("SELECT id FROM system_users WHERE id = #{userId} AND deleted = 0 FOR UPDATE")
    Long lockById(@Param("userId") Long userId);

    /** Password changes need a fresh value after acquiring business policy locks, even inside a cached SqlSession. */
    @Select("SELECT password FROM system_users WHERE id = #{userId} AND deleted = 0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String selectPasswordForUpdate(@Param("userId") Long userId);

    default AdminUserDO selectByUsername(String username) {
        return selectOne(AdminUserDO::getUsername, username);
    }

    default AdminUserDO selectByEmail(String email) {
        return selectOne(AdminUserDO::getEmail, email);
    }

    default AdminUserDO selectByMobile(String mobile) {
        return selectOne(AdminUserDO::getMobile, mobile);
    }

    /**
     * 查询当前租户下的同号账号。
     *
     * <p>mobile 在底座只有普通索引，历史数据可能存在多条；调用方必须显式处理歧义，
     * 不能使用 selectOne 隐式假设唯一。租户与逻辑删除条件由 MyBatis-Plus 拦截器统一注入。</p>
     */
    default List<AdminUserDO> selectListByMobile(String mobile) {
        return selectList(AdminUserDO::getMobile, mobile);
    }

    default PageResult<AdminUserDO> selectPage(UserPageReqVO reqVO, Collection<Long> deptIds, Collection<Long> userIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AdminUserDO>()
                .likeIfPresent(AdminUserDO::getUsername, reqVO.getUsername())
                .likeIfPresent(AdminUserDO::getMobile, reqVO.getMobile())
                .eqIfPresent(AdminUserDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(AdminUserDO::getCreateTime, reqVO.getCreateTime())
                .inIfPresent(AdminUserDO::getDeptId, deptIds)
                .inIfPresent(AdminUserDO::getId, userIds)
                .orderByDesc(AdminUserDO::getId));
    }

    default List<AdminUserDO> selectListByNickname(String nickname) {
        return selectList(new LambdaQueryWrapperX<AdminUserDO>().like(AdminUserDO::getNickname, nickname));
    }

    default List<AdminUserDO> selectListByStatus(Integer status) {
        return selectListByStatusAndDeptId(status, null);
    }

    default List<AdminUserDO> selectListByStatusAndDeptId(Integer status, Long deptId) {
        return selectList(new LambdaQueryWrapperX<AdminUserDO>()
                .eq(AdminUserDO::getStatus, status)
                .eqIfPresent(AdminUserDO::getDeptId, deptId));
    }

    default List<AdminUserDO> selectListByDeptIds(Collection<Long> deptIds) {
        return selectList(AdminUserDO::getDeptId, deptIds);
    }

}
