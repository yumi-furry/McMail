package com.example.mcmail.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 所有可配置项的唯一登记表（命令层 set/get/list、配置层定义、磁盘 reload 全部以这里为准）。
 * <p>
 * path 即配置文件中的点分键名，例如 {@code smtp.host}；
 * 以 {@code .password} 结尾的项视为敏感项，任何对外展示一律脱敏，落盘内容为明文的 Base64 编码。
 */
public enum ConfigKey {

    // ----- [general] -----
    GENERAL_ENABLED("general.enabled", Type.BOOL, "true"),
    GENERAL_DEBUG("general.debug", Type.BOOL, "false"),
    GENERAL_TIMEOUTMS("general.timeoutMs", Type.INT, "10000"),

    // ----- [smtp] -----
    SMTP_HOST("smtp.host", Type.STRING, ""),
    SMTP_PORT("smtp.port", Type.INT, "587"),
    SMTP_USERNAME("smtp.username", Type.STRING, ""),
    SMTP_PASSWORD("smtp.password", Type.STRING, ""),
    SMTP_FROMADDRESS("smtp.fromAddress", Type.STRING, ""),
    SMTP_USETLS("smtp.useTls", Type.BOOL, "false"),
    SMTP_USESSL("smtp.useSsl", Type.BOOL, "false"),

    // ----- [imap] -----
    IMAP_HOST("imap.host", Type.STRING, ""),
    IMAP_PORT("imap.port", Type.INT, "993"),
    IMAP_USERNAME("imap.username", Type.STRING, ""),
    IMAP_PASSWORD("imap.password", Type.STRING, ""),
    IMAP_USETLS("imap.useTls", Type.BOOL, "false"),
    IMAP_USESSL("imap.useSsl", Type.BOOL, "false"),
    IMAP_FOLDER("imap.folder", Type.STRING, "INBOX");

    /** 值类型，决定命令参数校验与 ForgeConfigSpec 的定义方式。 */
    public enum Type {
        BOOL, INT, STRING
    }

    private final String path;
    private final Type type;
    private final String defaultValue;
    private final boolean secret;

    /** 由 {@link McMailConfig} 静态初始化时绑定的 Forge 配置句柄。 */
    private ForgeConfigSpec.ConfigValue<?> handle;

    ConfigKey(String path, Type type, String defaultValue) {
        this.path = path;
        this.type = type;
        this.defaultValue = defaultValue;
        this.secret = path.endsWith(".password");
    }

    /** 点分完整键名，例如 {@code smtp.password}。 */
    public String path() {
        return path;
    }

    public Type type() {
        return type;
    }

    /** 默认值的字符串表示（BOOL/INT 也用字符串描述，解析时再转换）。 */
    public String defaultValue() {
        return defaultValue;
    }

    /** 是否为密码类敏感项。 */
    public boolean secret() {
        return secret;
    }

    /** TOML 节名，即第一个点之前的部分，例如 {@code smtp}。 */
    public String section() {
        int dot = path.indexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    /** 节内键名，即第一个点之后的部分，例如 {@code host}。 */
    public String leaf() {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** 按点分路径查找配置项；不存在返回 {@code null}（命令层据此报“未知配置项”）。 */
    public static ConfigKey byPath(String path) {
        if (path == null) {
            return null;
        }
        for (ConfigKey key : values()) {
            if (key.path.equals(path)) {
                return key;
            }
        }
        return null;
    }

    void attach(ForgeConfigSpec.ConfigValue<?> handle) {
        this.handle = handle;
    }

    /** 取得绑定的 Forge 配置句柄；类型由调用方按 {@link Type} 自行约束。 */
    @SuppressWarnings("unchecked")
    public <T> ForgeConfigSpec.ConfigValue<T> handle() {
        return (ForgeConfigSpec.ConfigValue<T>) this.handle;
    }
}
