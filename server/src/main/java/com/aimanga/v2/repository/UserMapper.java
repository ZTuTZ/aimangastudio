package com.aimanga.v2.repository;

import com.aimanga.v2.dto.UserVO;
import com.aimanga.v2.model.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper extends BaseMapper<User> {

    @Select("""
            SELECT u.id, u.username, u.role, u.status, u.create_time AS createTime,
                   (SELECT COUNT(*) FROM project p WHERE p.user_id = u.id) AS projectCount
            FROM user u
            ORDER BY u.id
            """)
    List<UserVO> listWithProjectCount();
}
