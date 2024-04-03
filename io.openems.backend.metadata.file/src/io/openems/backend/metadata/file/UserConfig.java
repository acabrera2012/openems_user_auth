package io.openems.backend.metadata.file;

import io.openems.common.session.Role;

public class UserConfig {
    private String username;
    private String name;
    private String password;
    private Role role;
    private String adgeApikey;

    public UserConfig(String username, String name, String password, Role role, String edgeApikey) {
        this.username = username;
        this.name = name;
        this.password = password;
        this.role = role;
        this.adgeApikey = edgeApikey;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getAdgeApikey() {
        return adgeApikey;
    }

    public void setAdgeApikey(String adgeApikey) {
        this.adgeApikey = adgeApikey;
    }
}
