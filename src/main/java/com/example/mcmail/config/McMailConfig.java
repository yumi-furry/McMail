package com.example.mcmail.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.example.mcmail.McMail;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 基于 {@link ForgeConfigSpec} 的集中配置管理。
 * <ul>
 *   <li>生成文件 {@code config/mcmail-common.toml}，分 [general]/[smtp]/[imap] 三节；</li>
 *   <li>{@code /mcmail set} 经 {@link #setValue(ConfigKey, String)} 校验后立即写盘；</li>
 *   <li>{@code /mcmail reload} 经 {@link #reloadFromDisk()} 从磁盘重新解析并回绑内存；</li>
 *   <li>密码项以 Base64 形式落盘，任何命令回显/日志只出现脱敏值 {@value #MASK}。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = McMail.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.DEDICATED_SERVER)
public final class McMailConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 配置文件名（Forge COMMON 类型默认即 modid-common.toml，这里显式指定）。 */
    public static final String FILE_NAME = "mcmail-common.toml";

    /** 密码对外展示时的脱敏值。 */
    public static final String MASK = "******";

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static {
        // 按 ConfigKey 登记表逐一定义，保证配置文件结构与命令支持的 key 永远一致。
        for (ConfigKey key : ConfigKey.values()) {
            BUILDER.push(key.section());
            ForgeConfigSpec.ConfigValue<?> value;
            switch (key.type()) {
                case BOOL -> value = BUILDER.define(key.leaf(), Boolean.parseBoolean(key.defaultValue()));
                case INT -> value = BUILDER.defineInRange(key.leaf(),
                        Integer.parseInt(key.defaultValue()), 1, Integer.MAX_VALUE);
                default -> value = BUILDER.define(key.leaf(), key.defaultValue());
            }
            key.attach(value);
            BUILDER.pop();
        }
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /** Forge 配置句柄，用于 {@code save()} 立即持久化；由 config 事件回调赋值。 */
    private static volatile ModConfig modConfig;

    private McMailConfig() {
    }

    // ---------------------------------------------------------------------
    // Forge 配置事件：缓存 ModConfig 实例（Loading 与外部变更触发的 Reloading 都会到这里）
    // ---------------------------------------------------------------------

    @SubscribeEvent
    public static void onLoading(final ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            modConfig = event.getConfig();
        }
    }

    @SubscribeEvent
    public static void onReloading(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            modConfig = event.getConfig();
        }
    }

    // ---------------------------------------------------------------------
    // 类型化读取（MailService 每次发信实时读取，天然支持热更新）
    // ---------------------------------------------------------------------

    public static String getString(ConfigKey key) {
        String value = key.<String>handle().get();
        return value == null ? "" : value;
    }

    public static boolean getBoolean(ConfigKey key) {
        return key.<Boolean>handle().get();
    }

    public static int getInt(ConfigKey key) {
        return key.<Integer>handle().get();
    }

    public static boolean isEnabled() {
        return getBoolean(ConfigKey.GENERAL_ENABLED);
    }

    public static boolean isDebug() {
        return getBoolean(ConfigKey.GENERAL_DEBUG);
    }

    /**
     * 读取密码项的明文。
     * <p>
     * 落盘约定为 Base64，但管理员可能手改文件直接写入明文——明文字符串本身可能恰好是合法
     * Base64（如 "ZR8G8inqi8aEDd6V"），因此解码成功不代表内容正确。策略：
     * 先尝试 Base64 解码，解码结果必须是合法 UTF-8 且重新编码后与原文一致，才视为编码后的密码；
     * 否则按原文使用，并顺手把明文重写为 Base64 落盘。
     */
    public static String getDecodedPassword(ConfigKey key) {
        if (!key.secret()) {
            throw new IllegalArgumentException("配置项 " + key.path() + " 不是密码项");
        }
        String stored = getString(key);
        if (stored.isEmpty()) {
            return "";
        }
        String decoded = tryDecodeBase64(stored);
        if (decoded != null) {
            return decoded;
        }
        // 明文场景：回写为 Base64，保证后续读取及配置文件本身都不出现明文。
        LOGGER.info("[McMail] 检测到 {} 为明文存储，正在转换为 Base64 ...", key.path());
        setValue(key, stored);
        return stored;
    }

    /**
     * 尝试把字符串当作 Base64 解码并还原为 UTF-8 文本。
     *
     * @return 成功且内容自洽时返回解码结果；否则返回 null
     */
    private static String tryDecodeBase64(String stored) {
        try {
            byte[] bytes = Base64.getDecoder().decode(stored);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
            // 自洽性校验：再编码回去必须逐字节一致，排除巧合命中的合法 Base64。
            String reEncoded = Base64.getEncoder().encodeToString(decoded.getBytes(StandardCharsets.UTF_8));
            return reEncoded.equals(stored) ? decoded : null;
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // 命令回显 / 列表（密码脱敏）
    // ---------------------------------------------------------------------

    /** 取得用于展示的值：密码项非空显示 ******，空值显示 (未设置)。 */
    public static String getDisplayValue(ConfigKey key) {
        if (key.secret()) {
            return getString(key).isEmpty() ? "(未设置)" : MASK;
        }
        Object value = key.handle().get();
        return value == null ? "" : String.valueOf(value);
    }

    /** 列出全部配置项的展示行（已脱敏），例如 {@code smtp.host = smtp.qq.com}。 */
    public static List<String> listDisplayLines() {
        List<String> lines = new ArrayList<>(ConfigKey.values().length);
        for (ConfigKey key : ConfigKey.values()) {
            lines.add(key.path() + " = " + getDisplayValue(key));
        }
        return lines;
    }

    // ---------------------------------------------------------------------
    // 写入（/mcmail set）：校验 -> 转 Base64(密码) -> 更新内存 -> 立即持久化
    // ---------------------------------------------------------------------

    /**
     * 校验并写入一个配置项，随后立即保存到磁盘。
     *
     * @throws IllegalArgumentException key 类型不接受该 rawValue 时
     */
    public static void setValue(ConfigKey key, String rawValue) {
        if (rawValue == null) {
            rawValue = "";
        }
        Object parsed;
        switch (key.type()) {
            case BOOL -> {
                if ("true".equals(rawValue)) {
                    parsed = Boolean.TRUE;
                } else if ("false".equals(rawValue)) {
                    parsed = Boolean.FALSE;
                } else {
                    throw new IllegalArgumentException(
                            "配置项 " + key.path() + " 只接受 true 或 false，实际输入：" + rawValue);
                }
            }
            case INT -> {
                int number;
                try {
                    number = Integer.parseInt(rawValue.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(
                            "配置项 " + key.path() + " 需要一个整数，实际输入：" + rawValue);
                }
                if (number < 1) {
                    throw new IllegalArgumentException(
                            "配置项 " + key.path() + " 必须是正整数，实际输入：" + rawValue);
                }
                parsed = number;
            }
            default -> parsed = rawValue;
        }

        if (key.secret()) {
            // 明文只在内存中短暂存在；落盘内容为 Base64 编码。
            parsed = Base64.getEncoder().encodeToString(
                    ((String) parsed).getBytes(StandardCharsets.UTF_8));
        }

        key.handle().set(parsed);
        save();

        if (!key.secret()) {
            LOGGER.info("[McMail] Config updated: {} = {}", key.path(), parsed);
        } else {
            // 密码绝不能以明文进入 Mod 日志。
            LOGGER.info("[McMail] Config updated: {} = {}", key.path(), MASK);
        }
    }

    /** 通过 Forge 把当前内存配置写回 config/mcmail-common.toml。 */
    private static void save() {
        ModConfig config = modConfig;
        if (config != null) {
            config.save();
        } else {
            LOGGER.warn("[McMail] ModConfig handle is not ready yet, value is only kept in memory");
        }
    }

    // ---------------------------------------------------------------------
    // 重载（/mcmail reload）：直接重新解析磁盘文件并回绑内存
    // ---------------------------------------------------------------------

    /**
     * 从 {@code config/mcmail-common.toml} 重新读取全部配置项并回绑内存。
     * 文件不存在时用 Forge 重新生成一份默认配置。
     *
     * @return 回绑的配置项数量
     * @throws IllegalStateException 文件内容存在类型错误时
     */
    public static int reloadFromDisk() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.exists(path)) {
            LOGGER.warn("[McMail] {} does not exist, regenerating default config", FILE_NAME);
            save();
            return ConfigKey.values().length;
        }

        final CommentedFileConfig fileConfig = CommentedFileConfig.builder(path.toFile()).build();
        try {
            fileConfig.load();
            for (ConfigKey key : ConfigKey.values()) {
                List<String> tomlPath = Arrays.asList(key.path().split("\\."));
                Object stored = fileConfig.contains(tomlPath) ? fileConfig.get(tomlPath) : null;
                if (stored == null) {
                    stored = parseDefault(key);
                }
                key.handle().set(normalize(key, stored));
            }
        } finally {
            fileConfig.close();
        }
        LOGGER.info("[McMail] Config reloaded from {}", FILE_NAME);
        return ConfigKey.values().length;
    }

    /** 把 TOML 中读出的原始值规范成 ForgeConfigSpec 对应类型；密码项保持 Base64 原样，不重复编码。 */
    private static Object normalize(ConfigKey key, Object stored) {
        switch (key.type()) {
            case BOOL -> {
                if (stored instanceof Boolean bool) {
                    return bool;
                }
                if ("true".equalsIgnoreCase(String.valueOf(stored))) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(String.valueOf(stored))) {
                    return Boolean.FALSE;
                }
                throw new IllegalStateException(
                        "配置文件中 " + key.path() + " 必须是 true/false，实际值：" + stored);
            }
            case INT -> {
                if (stored instanceof Number number) {
                    long value = number.longValue();
                    if (value < 1 || value > Integer.MAX_VALUE) {
                        throw new IllegalStateException(
                                "配置文件中 " + key.path() + " 超出范围(1~" + Integer.MAX_VALUE + ")：" + value);
                    }
                    return (int) value;
                }
                try {
                    return Integer.parseInt(String.valueOf(stored).trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalStateException(
                            "配置文件中 " + key.path() + " 必须是整数，实际值：" + stored);
                }
            }
            default -> {
                return String.valueOf(stored);
            }
        }
    }

    private static Object parseDefault(ConfigKey key) {
        return switch (key.type()) {
            case BOOL -> Boolean.parseBoolean(key.defaultValue());
            case INT -> Integer.parseInt(key.defaultValue());
            case STRING -> key.defaultValue();
        };
    }
}
