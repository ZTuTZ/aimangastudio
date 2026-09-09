package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.UserVO;
import com.aimanga.v2.model.User;
import com.aimanga.v2.repository.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    private final JdbcTemplate jdbcTemplate;

    public UserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public User findByUsername(String username) {
        return getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    public List<UserVO> listWithProjectCount() {
        return baseMapper.listWithProjectCount();
    }

    public User createUser(String username, String rawPassword, String role) {
        if (findByUsername(username) != null) {
            throw new BusinessException(409, "用户名已存在: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        user.setRole(role);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        save(user);
        return user;
    }

    public void updateStatus(Long id, Integer status) {
        User user = getById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + id);
        }
        User patch = new User();
        patch.setId(id);
        patch.setStatus(status);
        updateById(patch);
    }

    public void updatePassword(Long id, String rawPassword) {
        User patch = new User();
        patch.setId(id);
        patch.setPasswordHash(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        updateById(patch);
    }

    /** 该用户名下的作品数(Phase 3 建立 ProjectService 后迁移过去) */
    public long countProjectsOf(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public boolean verifyPassword(User user, String rawPassword) {
        return user.getPasswordHash() != null && BCrypt.checkpw(rawPassword, user.getPasswordHash());
    }
}
