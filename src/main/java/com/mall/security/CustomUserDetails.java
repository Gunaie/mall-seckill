package com.mall.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mall.entity.UmsUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spring Security 用户主体：包装用户实体 + 角色 + 权限
 * <p>
 * 角色以 {@code ROLE_} 前缀存入 GrantedAuthority，可配合 hasRole('ADMIN')；
 * 权限标识（如 ums:user:list）可配合 hasAuthority(...)。
 *
 * @author mall
 */
@Getter
public class CustomUserDetails implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String username;
    @JsonIgnore
    private final String password;
    private final Integer status;
    private final List<String> roles;
    private final List<String> permissions;
    private final Set<SimpleGrantedAuthority> authorities;

    public CustomUserDetails(UmsUser user, List<String> roles, List<String> permissions) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.status = user.getStatus();
        this.roles = roles;
        this.permissions = permissions;
        // 角色 + 细粒度权限统一放入 GrantedAuthority
        this.authorities = Stream.concat(
                        roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)),
                        permissions.stream().map(SimpleGrantedAuthority::new))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /**
     * status=1 才允许登录
     */
    @Override
    public boolean isEnabled() {
        return status != null && status == 1;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
