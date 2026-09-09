package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.CreateUserRequest;
import com.aimanga.v2.dto.DeleteUserRequest;
import com.aimanga.v2.dto.UpdateUserRequest;
import com.aimanga.v2.dto.UserVO;
import com.aimanga.v2.model.User;
import com.aimanga.v2.security.CurrentUser;
import com.aimanga.v2.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端账号管理(仅 ADMIN,由 Shiro admin 过滤器把守)。
 * 删除规则(默认不级联):需重复输入用户名确认;不能删自己;名下有作品时拒绝(提示改用停用)。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public Result<List<UserVO>> list() {
        return Result.ok(userService.listWithProjectCount());
    }

    @PostMapping
    public Result<UserVO> create(@Valid @RequestBody CreateUserRequest request) {
        User created = userService.createUser(request.username(), request.password(), request.role());
        log.info("[admin] 创建用户 {} 角色 {}", created.getUsername(), created.getRole());
        UserVO vo = new UserVO();
        vo.setId(created.getId());
        vo.setUsername(created.getUsername());
        vo.setRole(created.getRole());
        vo.setStatus(created.getStatus());
        vo.setCreateTime(created.getCreateTime());
        vo.setProjectCount(0L);
        return Result.ok(vo);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + id);
        }
        if (request.status() != null) {
            if (request.status() != 0 && request.status() != 1) {
                throw new BusinessException(400, "status 只能为 0(停用)或 1(启用)");
            }
            if (request.status() == 0 && id.equals(CurrentUser.id())) {
                throw new BusinessException(409, "不能停用当前登录账号");
            }
            userService.updateStatus(id, request.status());
        }
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            userService.updatePassword(id, request.newPassword());
        }
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestBody(required = false) DeleteUserRequest request) {
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + id);
        }
        if (id.equals(CurrentUser.id())) {
            throw new BusinessException(409, "不能删除当前登录账号");
        }
        String confirm = request == null ? null : request.confirmUsername();
        if (confirm == null || !confirm.equals(user.getUsername())) {
            throw new BusinessException(400, "请重复输入用户名以确认删除");
        }
        long projects = userService.countProjectsOf(id);
        if (projects > 0) {
            throw new BusinessException(409,
                    "该用户名下有 " + projects + " 部作品,为避免数据丢失不能直接删除,请改用停用");
        }
        userService.removeById(id);
        log.info("[admin] 删除用户 {}", user.getUsername());
        return Result.ok();
    }
}
