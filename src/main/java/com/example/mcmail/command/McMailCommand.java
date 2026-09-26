package com.example.mcmail.command;

import com.example.mcmail.config.ConfigKey;
import com.example.mcmail.config.McMailConfig;
import com.example.mcmail.mail.MailResult;
import com.example.mcmail.mail.MailService;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /mcmail} 命令树。
 * <p>
 * 根节点统一要求 {@code hasPermissionLevel(4)}：控制台恒为 true、OP 等级 4 为 true；
 * 普通玩家不满足时该命令对其不可见/不可解析（Brigadier 原生行为）。
 * 所有测试类子命令异步执行，结果在服务器主线程回发给执行者，绝不卡主线程。
 */
public final class McMailCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "[McMail] ";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private McMailCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mcmail")
                        // 1.20.1 official mappings 的权限检查即 hasPermission(int)：
                        // 控制台恒为等级 4，OP 等级 4 通过，普通玩家不通过（命令对其不可见/不可解析）。
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(McMailCommand::setConfig))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .executes(McMailCommand::getConfig)))
                        .then(Commands.literal("list")
                                .executes(McMailCommand::listConfig))
                        .then(Commands.literal("test")
                                .executes(McMailCommand::testAll)
                                .then(Commands.literal("smtp").executes(McMailCommand::testSmtp))
                                .then(Commands.literal("imap").executes(McMailCommand::testImap))
                                .then(Commands.literal("send")
                                        .then(Commands.argument("to", StringArgumentType.greedyString())    
                                                .executes(McMailCommand::testSend))))
                        .then(Commands.literal("reload")
                                .executes(McMailCommand::reloadConfig))
        );
    }

    // ---------------------------------------------------------------------
    // set
    // ---------------------------------------------------------------------

    private static int setConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String keyPath = StringArgumentType.getString(ctx, "key");
        String rawValue = StringArgumentType.getString(ctx, "value");

        ConfigKey key = ConfigKey.byPath(keyPath);
        if (key == null) {
            return fail(source, "未知配置项：" + keyPath);
        }

        try {
            McMailConfig.setValue(key, rawValue);
        } catch (IllegalArgumentException ex) {
            return fail(source, ex.getMessage());
        }

        // 密码回显脱敏（setValue 内部落盘为 Base64，这里也只可能看到 ******）。
        success(source, "已设置 " + key.path() + " = " + McMailConfig.getDisplayValue(key));

        if (key.secret() && source.getEntity() instanceof Player) {
            // 仅对游戏内玩家提示；控制台天然不会被聊天日志记录命令。
            success(source, "安全提示：聊天栏输入的密码命令会被原版服务器日志记录原文，"
                    + "建议在控制台执行 set password，或直接写入配置文件 " + McMailConfig.FILE_NAME);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------
    // get / list
    // ---------------------------------------------------------------------

    private static int getConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String keyPath = StringArgumentType.getString(ctx, "key");

        ConfigKey key = ConfigKey.byPath(keyPath);
        if (key == null) {
            return fail(source, "未知配置项：" + keyPath);
        }
        return success(source, key.path() + " = " + McMailConfig.getDisplayValue(key));
    }

    private static int listConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MutableComponent message = Component.literal(PREFIX + "当前配置（密码已脱敏）：");
        for (String line : McMailConfig.listDisplayLines()) {
            // append 返回组件自身，原地累加即可保持变量 effectively final。
            message.append("\n").append(line);
        }
        source.sendSuccess(() -> message, false);
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------
    // reload
    // ---------------------------------------------------------------------

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            int count = McMailConfig.reloadFromDisk();
            return success(source, "已从 " + McMailConfig.FILE_NAME + " 重新加载 " + count + " 个配置项");
        } catch (Exception ex) {
            LOGGER.error("[McMail] Config reload failed", ex);
            return fail(source, "重载失败：" + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // test（全部异步，结果回主线程发送）
    // ---------------------------------------------------------------------

    private static int testSmtp(CommandContext<CommandSourceStack> ctx) {
        return dispatchTest(ctx.getSource(), MailService.getInstance().testSmtp());
    }

    private static int testImap(CommandContext<CommandSourceStack> ctx) {
        return dispatchTest(ctx.getSource(), MailService.getInstance().testImap());
    }

    private static int testAll(CommandContext<CommandSourceStack> ctx) {
        MailService service = MailService.getInstance();
        // 顺序执行：先 SMTP 后 IMAP；即使 SMTP 失败也继续测 IMAP，最终合并两项结果。
        CompletableFuture<MailResult> combined = service.testSmtp()
                .handle(McMailCommand::wrapResult)
                .thenCompose(smtpResult -> service.testImap()
                        .handle(McMailCommand::wrapResult)
                        .thenApply(imapResult -> new MailResult(
                                smtpResult.success() && imapResult.success(),
                                smtpResult.message() + "\n" + imapResult.message())));
        return dispatchTest(ctx.getSource(), combined);
    }

    private static int testSend(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String to = StringArgumentType.getString(ctx, "to").trim();
        String html = com.example.mcmail.mail.TestMailTemplate.render();

        CompletableFuture<MailResult> future = MailService.getInstance()
                .sendHtmlMail(to, "McMail Test", html)
                .thenApply(result -> result.success()
                        ? MailResult.success("测试邮件已发送到 " + to)
                        : result);
        return dispatchTest(source, future);
    }

    /**
     * 提交异步测试并在完成后回到服务器主线程反馈：
     * 控制台恒可收信；游戏内执行者若已离线则跳过，避免对失效连接发包。
     */
    private static int dispatchTest(CommandSourceStack source, CompletableFuture<MailResult> future) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal(PREFIX + "测试任务已提交，正在异步执行，请稍候..."), false);

        future.whenComplete((result, error) -> server.execute(() -> {
            if (source.getEntity() instanceof ServerPlayer player
                    && server.getPlayerList().getPlayer(player.getUUID()) == null) {
                return;
            }
            MailResult actual = result != null
                    ? result
                    : MailResult.fail("测试异常：" + (error == null ? "未知错误" : error.getMessage()));
            Component component = Component.literal(PREFIX + actual.message());
            if (actual.success()) {
                source.sendSuccess(() -> component, false);
            } else {
                source.sendFailure(component);
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private static MailResult wrapResult(MailResult result, Throwable error) {
        if (result != null) {
            return result;
        }
        return MailResult.fail("测试异常：" + (error == null ? "未知错误" : error.getMessage()));
    }

    // ---------------------------------------------------------------------
    // 回显辅助
    // ---------------------------------------------------------------------

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(PREFIX + message), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(PREFIX + message));
        return 0;
    }
}
