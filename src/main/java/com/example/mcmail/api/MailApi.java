package com.example.mcmail.api;

import com.example.mcmail.mail.MailResult;
import com.example.mcmail.mail.MailService;

import java.util.concurrent.CompletableFuture;

/**
 * McMail 对外邮件 API —— 供其他 Mod 调用的唯一入口。
 *
 * <h2>用途</h2>
 * 其他 Mod 不需要关心 IMAP/SMTP 配置从哪里来，管理员通过
 * {@code config/mcmail-common.toml} 或 OP 命令 {@code /mcmail set ...} 完成配置后，
 * 直接调用本类的静态方法即可异步发信。
 *
 * <h2>权限说明</h2>
 * 本 API 是服务端代码级接口，<b>不受 {@code /mcmail} 命令权限（权限等级 4）影响</b>；
 * McMail 本身不提供任何面向普通玩家的命令、物品、GUI 或聊天入口，
 * 调用方 Mod 必须自行保证只有受信任的逻辑才触发发信。
 *
 * <h2>行为约定</h2>
 * <ul>
 *   <li>全程异步，网络 IO 在 McMail 专用守护线程池执行，不阻塞服务器主线程；</li>
 *   <li>{@code general.enabled=false} 时，返回的 future 以失败结果
 *       “邮件功能未启用”立即完成，不会发起任何网络连接；</li>
 *   <li>SMTP 未配置完整、服务器尚未启动完成时同样立即失败；</li>
 *   <li>任何网络/认证异常都会被转成 {@link MailResult}，不会抛出到调用线程。</li>
 * </ul>
 *
 * <h2>编译期依赖示例（Gradle）</h2>
 * <pre>{@code
 * // 其他 Mod 只需 compileOnly 依赖 McMail 的 jar（运行时由服务端安装 McMail 提供）
 * compileOnly files('libs/mcmail-1.0.0.jar')
 * }</pre>
 *
 * <h2>调用示例</h2>
 * <pre>{@code
 * MailApi.sendMail("user@example.com", "服务器通知", "欢迎来到服务器！")
 *        .thenAccept(result -> {
 *            if (!result.success()) {
 *                // 记录 result.message()，例如“SMTP 认证失败……”
 *            }
 *        });
 * }</pre>
 */
public final class MailApi {

    private MailApi() {
    }

    /**
     * 异步发送一封纯文本邮件。
     *
     * @param to      收件人邮箱地址，不能为空白
     * @param subject 邮件主题（可为空串）
     * @param body    纯文本邮件正文（可为空串）
     * @return 异步结果；{@link MailResult#success()} 为是否成功，
     *         {@link MailResult#message()} 为面向管理员的中文描述
     */
    public static CompletableFuture<MailResult> sendMail(String to, String subject, String body) {
        return MailService.getInstance().sendMail(to, subject, body);
    }

    /**
     * 异步发送一封 HTML 邮件。McMail logo 会以 CID 附件形式内嵌，
     * HTML 中通过 {@code <img src="cid:mcmail-logo" ...>} 引用即可。
     *
     * @param to      收件人邮箱地址，不能为空白
     * @param subject 邮件主题（可为空串）
     * @param html    HTML 邮件正文，应为完整 html 文档（可为空串）
     * @return 异步结果；{@link MailResult#success()} 为是否成功，
     *         {@link MailResult#message()} 为面向管理员的中文描述
     */
    public static CompletableFuture<MailResult> sendHtmlMail(String to, String subject, String html) {
        return MailService.getInstance().sendHtmlMail(to, subject, html);
    }
}
