package com.example.mcmail.mail;

import com.example.mcmail.config.ConfigKey;
import com.example.mcmail.config.McMailConfig;
import com.mojang.logging.LogUtils;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 邮件服务（单例）。
 * <ul>
 *   <li>所有 SMTP/IMAP 网络 IO 都在专用守护线程池上异步执行，绝不阻塞服务器主线程；</li>
 *   <li>{@code general.enabled=false} 时立即返回“邮件功能未启用”，不发起任何网络连接；</li>
 *   <li>所有异常都被转换为 {@link MailResult}，不会逃逸到主线程；</li>
 *   <li>日志只记录主机/端口/收件人，绝不记录密码、授权码与邮件正文。</li>
 * </ul>
 */
public final class MailService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MailService INSTANCE = new MailService();

    /** jar 内 logo 资源路径。 */
    private static final String LOGO_RESOURCE = "/assets/mcmail/logo.png";
    /** HTML 邮件中引用内嵌 logo 的 Content-ID。 */
    private static final String LOGO_CID = "mcmail-logo";

    /** 邮件 IO 专用线程池：连接测试与发信互不等靠，2 个线程足够管理员工具使用。 */
    private final AtomicInteger threadCounter = new AtomicInteger();
    private volatile ExecutorService executor;

    private MailService() {
    }

    public static MailService getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------------
    // 生命周期（由主类在 ServerStartedEvent / ServerStoppingEvent 调用）
    // ---------------------------------------------------------------------

    /** 初始化线程池。重复调用安全。 */
    public synchronized void init() {
        if (executor != null) {
            return;
        }
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "mcmail-mail-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(2, factory);
        LOGGER.info("[McMail] MailService initialized");
    }

    /** 优雅关闭线程池：等待最多 2 秒让在途邮件结束，然后强制停止。 */
    public synchronized void shutdown() {
        ExecutorService current = executor;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(2L, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException ex) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
        LOGGER.info("[McMail] MailService shut down");
    }

    // ---------------------------------------------------------------------
    // 对外异步操作
    // ---------------------------------------------------------------------

    /**
     * 异步发送邮件。
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param body    纯文本正文
     * @return 完成后回调的结果；服务未就绪/功能未启用/配置不完整时立即以失败结果完成，不连网
     */
    public CompletableFuture<MailResult> sendMail(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return CompletableFuture.completedFuture(MailResult.fail("收件人地址不能为空"));
        }
        SmtpSettings smtp;
        try {
            MailResult guard = guard();
            if (guard != null) {
                return CompletableFuture.completedFuture(guard);
            }
            smtp = SmtpSettings.snapshot();
        } catch (IllegalStateException ex) {
            return CompletableFuture.completedFuture(MailResult.fail(ex.getMessage()));
        }
        if (!smtp.isUsable()) {
            return CompletableFuture.completedFuture(
                    MailResult.fail("SMTP 配置不完整，请先设置 smtp.host/username/password/fromAddress"));
        }
        final String safeSubject = subject == null ? "" : subject;
        final String safeBody = body == null ? "" : body;
        return submit(() -> doSend(smtp, to.trim(), safeSubject, safeBody));
    }

    /**
     * 异步发送 HTML 邮件。logo 以 CID 内嵌附件方式随信携带，
     * HTML 中通过 {@code <img src="cid:mcmail-logo">} 引用。
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param html    HTML 正文（完整 html 文档）
     * @return 完成后回调的结果；前置校验失败时立即以失败结果完成，不连网
     */
    public CompletableFuture<MailResult> sendHtmlMail(String to, String subject, String html) {
        if (to == null || to.isBlank()) {
            return CompletableFuture.completedFuture(MailResult.fail("收件人地址不能为空"));
        }
        SmtpSettings smtp;
        try {
            MailResult guard = guard();
            if (guard != null) {
                return CompletableFuture.completedFuture(guard);
            }
            smtp = SmtpSettings.snapshot();
        } catch (IllegalStateException ex) {
            return CompletableFuture.completedFuture(MailResult.fail(ex.getMessage()));
        }
        if (!smtp.isUsable()) {
            return CompletableFuture.completedFuture(
                    MailResult.fail("SMTP 配置不完整，请先设置 smtp.host/username/password/fromAddress"));
        }
        final String safeSubject = subject == null ? "" : subject;
        final String safeHtml = html == null ? "" : html;
        return submit(() -> doSendHtml(smtp, to.trim(), safeSubject, safeHtml));
    }

    /** 异步测试 SMTP：建立连接并完成登录后立即关闭。 */
    public CompletableFuture<MailResult> testSmtp() {
        SmtpSettings smtp;
        try {
            MailResult guard = guard();
            if (guard != null) {
                return CompletableFuture.completedFuture(guard);
            }
            smtp = SmtpSettings.snapshot();
        } catch (IllegalStateException ex) {
            return CompletableFuture.completedFuture(MailResult.fail(ex.getMessage()));
        }
        if (!smtp.isUsable()) {
            return CompletableFuture.completedFuture(
                    MailResult.fail("SMTP 配置不完整，请先设置 smtp.host/username/password/fromAddress"));
        }
        return submit(() -> doTestSmtp(smtp));
    }

    /** 异步测试 IMAP：建立连接、登录，并以只读方式打开收件箱文件夹。 */
    public CompletableFuture<MailResult> testImap() {
        ImapSettings imap;
        try {
            MailResult guard = guard();
            if (guard != null) {
                return CompletableFuture.completedFuture(guard);
            }
            imap = ImapSettings.snapshot();
        } catch (IllegalStateException ex) {
            return CompletableFuture.completedFuture(MailResult.fail(ex.getMessage()));
        }
        if (!imap.isUsable()) {
            return CompletableFuture.completedFuture(
                    MailResult.fail("IMAP 配置不完整，请先设置 imap.host/username/password"));
        }
        return submit(() -> doTestImap(imap));
    }

    /** 通用前置检查：线程池就绪 + 功能开关已打开。返回非 null 表示应当直接失败返回。 */
    private MailResult guard() {
        if (executor == null) {
            return MailResult.fail("邮件服务未就绪（服务器尚未完成启动）");
        }
        if (!McMailConfig.isEnabled()) {
            // 明确要求：未启用时不进行任何网络连接。
            return MailResult.fail("邮件功能未启用（general.enabled = false）");
        }
        return null;
    }

    /** 把任务提交到邮件线程池；任务内部也会兜底捕获一切 Throwable。 */
    private CompletableFuture<MailResult> submit(MailTask task) {
        ExecutorService pool = executor;
        if (pool == null) {
            return CompletableFuture.completedFuture(MailResult.fail("邮件服务未就绪（服务器尚未完成启动）"));
        }
        CompletableFuture<MailResult> future = new CompletableFuture<>();
        pool.submit(() -> {
            try {
                future.complete(task.run());
            } catch (Throwable t) {
                LOGGER.error("[McMail] Unexpected mail task failure", t);
                future.complete(MailResult.fail("内部错误：" + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " - " + t.getMessage() : "")));
            }
        });
        return future;
    }

    // ---------------------------------------------------------------------
    // SMTP
    // ---------------------------------------------------------------------

    private MailResult doSend(SmtpSettings smtp, String to, String subject, String body) {
        Session session = Session.getInstance(buildSmtpProperties(smtp));
        session.setDebug(McMailConfig.isDebug());
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtp.fromAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");

            LOGGER.info("[McMail] Sending mail to {} via {}:{}", to, smtp.host, smtp.port);
            Transport.send(message, smtp.username, smtp.password);
            return MailResult.success("邮件已发送到 " + to);
        } catch (MessagingException ex) {
            LOGGER.warn("[McMail] Send failed via {}:{} - {}", smtp.host, smtp.port, safeMessage(ex));
            return MailResult.fail(describe(ex, "SMTP", smtp.host, smtp.port));
        }
    }

    private MailResult doSendHtml(SmtpSettings smtp, String to, String subject, String html) {
        Session session = Session.getInstance(buildSmtpProperties(smtp));
        session.setDebug(McMailConfig.isDebug());
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtp.fromAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");

            // multipart/related：HTML 正文 + CID 内嵌 logo，避免依赖外链图床。
            MimeMultipart multipart = new MimeMultipart("related");

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=UTF-8");
            multipart.addBodyPart(htmlPart);

            byte[] logoBytes = loadLogoBytes();
            if (logoBytes != null) {
                MimeBodyPart logoPart = new MimeBodyPart();
                logoPart.setDataHandler(new DataHandler(new ByteArrayDataSource(logoBytes, "image/png")));
                logoPart.setHeader("Content-ID", "<" + LOGO_CID + ">");
                logoPart.setDisposition(MimeBodyPart.INLINE);
                logoPart.setFileName("logo.png");
                multipart.addBodyPart(logoPart);
            }

            message.setContent(multipart);

            LOGGER.info("[McMail] Sending HTML mail to {} via {}:{}", to, smtp.host, smtp.port);
            Transport.send(message, smtp.username, smtp.password);
            return MailResult.success("邮件已发送到 " + to);
        } catch (MessagingException ex) {
            LOGGER.warn("[McMail] Send HTML failed via {}:{} - {}", smtp.host, smtp.port, safeMessage(ex));
            return MailResult.fail(describe(ex, "SMTP", smtp.host, smtp.port));
        }
    }

    /** 从 jar 内资源读取 logo 字节；读取失败返回 null（HTML 邮件仍可发送，只是没有图）。 */
    private static byte[] loadLogoBytes() {
        try (InputStream in = MailService.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[McMail] Logo resource {} not found", LOGO_RESOURCE);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            LOGGER.warn("[McMail] Failed to load logo resource {}: {}", LOGO_RESOURCE, ex.getMessage());
            return null;
        }
    }

    private MailResult doTestSmtp(SmtpSettings smtp) {
        Session session = Session.getInstance(buildSmtpProperties(smtp));
        session.setDebug(McMailConfig.isDebug());
        Transport transport = null;
        try {
            transport = session.getTransport("smtp");
            transport.connect(smtp.host, smtp.port, smtp.username, smtp.password);
            LOGGER.info("[McMail] SMTP test ok: {}:{}", smtp.host, smtp.port);
            return MailResult.success("SMTP 连接成功 (" + smtp.host + ":" + smtp.port + ")");
        } catch (MessagingException ex) {
            LOGGER.warn("[McMail] SMTP test failed: {}:{} - {}", smtp.host, smtp.port, safeMessage(ex));
            return MailResult.fail(describe(ex, "SMTP", smtp.host, smtp.port));
        } finally {
            closeQuietly(transport);
        }
    }

    private Properties buildSmtpProperties(SmtpSettings smtp) {
        Properties props = new Properties();
        props.setProperty("mail.smtp.host", smtp.host);
        props.setProperty("mail.smtp.port", String.valueOf(smtp.port));
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(smtp.timeoutMs));
        props.setProperty("mail.smtp.timeout", String.valueOf(smtp.timeoutMs));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(smtp.timeoutMs));
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(smtp.useTls));
        props.setProperty("mail.smtp.ssl.enable", String.valueOf(smtp.useSsl));
        return props;
    }

    // ---------------------------------------------------------------------
    // IMAP
    // ---------------------------------------------------------------------

    private MailResult doTestImap(ImapSettings imap) {
        Session session = Session.getInstance(buildImapProperties(imap));
        session.setDebug(McMailConfig.isDebug());
        Store store = null;
        Folder folder = null;
        try {
            // SSL 走 imaps 协议（如 QQ 993），否则普通 imap + 可选 STARTTLS。
            store = session.getStore(imap.useSsl ? "imaps" : "imap");
            store.connect(imap.host, imap.port, imap.username, imap.password);
            LOGGER.info("[McMail] IMAP login ok: {}:{}", imap.host, imap.port);

            String folderName = (imap.folder == null || imap.folder.isBlank()) ? "INBOX" : imap.folder;
            try {
                folder = store.getFolder(folderName);
                folder.open(Folder.READ_ONLY);
            } catch (MessagingException ex) {
                LOGGER.warn("[McMail] IMAP folder open failed: {} - {}", folderName, safeMessage(ex));
                return MailResult.fail("IMAP 登录成功，但文件夹 " + folderName + " 打开失败：" + safeMessage(ex));
            }
            return MailResult.success("IMAP 连接成功 (" + imap.host + ":" + imap.port + ")");
        } catch (MessagingException ex) {
            LOGGER.warn("[McMail] IMAP test failed: {}:{} - {}", imap.host, imap.port, safeMessage(ex));
            return MailResult.fail(describe(ex, "IMAP", imap.host, imap.port));
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    private Properties buildImapProperties(ImapSettings imap) {
        Properties props = new Properties();
        props.setProperty("mail.imap.host", imap.host);
        props.setProperty("mail.imap.port", String.valueOf(imap.port));
        props.setProperty("mail.imap.connectiontimeout", String.valueOf(imap.timeoutMs));
        props.setProperty("mail.imap.timeout", String.valueOf(imap.timeoutMs));
        props.setProperty("mail.imap.starttls.enable", String.valueOf(imap.useTls));
        props.setProperty("mail.imap.ssl.enable", String.valueOf(imap.useSsl));
        // imaps 协议同样读取上述部分属性，关键 SSL/超时项显式补一份。
        props.setProperty("mail.imaps.connectiontimeout", String.valueOf(imap.timeoutMs));
        props.setProperty("mail.imaps.timeout", String.valueOf(imap.timeoutMs));
        props.setProperty("mail.imaps.ssl.enable", String.valueOf(imap.useSsl));
        return props;
    }

    // ---------------------------------------------------------------------
    // 异常分类与资源清理
    // ---------------------------------------------------------------------

    /**
     * 把邮件异常映射成中文管理员可读原因。沿 cause 链识别常见网络/认证问题：
     * 认证失败 / 未知主机 / 连接超时 / 连接被拒绝 / 其他 MessagingException。
     */
    private static String describe(Throwable throwable, String protocol, String host, int port) {
        Throwable cause = throwable;
        int depth = 0;
        while (cause != null && depth++ < 20) {
            if (cause instanceof AuthenticationFailedException) {
                return protocol + " 认证失败：用户名或密码错误（如使用 QQ/163 邮箱，请填写授权码而非登录密码）";
            }
            if (cause instanceof UnknownHostException) {
                return "未知主机：" + host + "，请检查 " + protocol.toLowerCase() + ".host 与 DNS";
            }
            if (cause instanceof SocketTimeoutException) {
                // SSL 端口（465/993）未开 useSsl 时双方协议互等，最终也表现为读超时，
                // 这里直接提示检查 SSL 开关，避免管理员被"调大超时"误导。
                return "连接/响应超时 (" + host + ":" + port + ")。"
                        + "若该端口为 SSL 端口（SMTP 465 / IMAP 993），请确认对应的 useSsl = true；"
                        + "否则可调大 general.timeoutMs";
            }
            if (cause instanceof ConnectException) {
                return "连接被拒绝 (" + host + ":" + port + ")，请检查主机、端口与 SSL/TLS 开关";
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        String detail = safeMessage(throwable);
        return protocol + " 错误：" + (detail.isBlank() ? throwable.getClass().getSimpleName() : detail);
    }

    /** 提取异常描述，并阻止任何潜在的敏感原文进入展示。 */
    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? "" : message;
    }

    private static void closeQuietly(Transport transport) {
        if (transport != null && transport.isConnected()) {
            try {
                transport.close();
            } catch (MessagingException ignored) {
                // 关闭连接时的异常无需上报
            }
        }
    }

    private static void closeQuietly(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // 关闭连接时的异常无需上报
            }
        }
    }

    private static void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (MessagingException ignored) {
                // 关闭文件夹时的异常无需上报
            }
        }
    }

    // ---------------------------------------------------------------------
    // 配置快照（在异步线程内一次性取齐，避免发信过程中配置被改动）
    // ---------------------------------------------------------------------

    private record SmtpSettings(String host, int port, String username, String password,
                                String fromAddress, boolean useTls, boolean useSsl, int timeoutMs) {

        static SmtpSettings snapshot() {
            return new SmtpSettings(
                    McMailConfig.getString(ConfigKey.SMTP_HOST),
                    McMailConfig.getInt(ConfigKey.SMTP_PORT),
                    McMailConfig.getString(ConfigKey.SMTP_USERNAME),
                    McMailConfig.getDecodedPassword(ConfigKey.SMTP_PASSWORD),
                    McMailConfig.getString(ConfigKey.SMTP_FROMADDRESS),
                    McMailConfig.getBoolean(ConfigKey.SMTP_USETLS),
                    McMailConfig.getBoolean(ConfigKey.SMTP_USESSL),
                    McMailConfig.getInt(ConfigKey.GENERAL_TIMEOUTMS));
        }

        boolean isUsable() {
            return !host.isBlank() && !username.isBlank() && !password.isBlank() && !fromAddress.isBlank();
        }
    }

    private record ImapSettings(String host, int port, String username, String password,
                                boolean useTls, boolean useSsl, String folder, int timeoutMs) {

        static ImapSettings snapshot() {
            return new ImapSettings(
                    McMailConfig.getString(ConfigKey.IMAP_HOST),
                    McMailConfig.getInt(ConfigKey.IMAP_PORT),
                    McMailConfig.getString(ConfigKey.IMAP_USERNAME),
                    McMailConfig.getDecodedPassword(ConfigKey.IMAP_PASSWORD),
                    McMailConfig.getBoolean(ConfigKey.IMAP_USETLS),
                    McMailConfig.getBoolean(ConfigKey.IMAP_USESSL),
                    McMailConfig.getString(ConfigKey.IMAP_FOLDER),
                    McMailConfig.getInt(ConfigKey.GENERAL_TIMEOUTMS));
        }

        boolean isUsable() {
            return !host.isBlank() && !username.isBlank() && !password.isBlank();
        }
    }

    /** 允许抛检异常的异步任务。 */
    @FunctionalInterface
    private interface MailTask {
        MailResult run() throws Exception;
    }
}
