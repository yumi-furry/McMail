package com.example.mcmail.mail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 测试邮件 HTML 模板渲染器。
 * <p>
 * 模板文件为 jar 内资源 {@code /assets/mcmail/test-mail.html}，
 * 首次使用时读入并缓存，之后仅替换占位符 {@code {{SERVER_TIME}}}。
 * 模板中的 logo 通过 {@code cid:mcmail-logo} 引用，
 * 由 {@link MailService#sendHtmlMail} 自动以附件形式内嵌。
 * </p>
 */
public final class TestMailTemplate {

    private static final String TEMPLATE_RESOURCE = "/assets/mcmail/test-mail.html";
    private static final String PLACEHOLDER_SERVER_TIME = "{{SERVER_TIME}}";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 缓存的模板原文（含占位符），首次加载后不再重复 IO。 */
    private static volatile String template;

    private TestMailTemplate() {
    }

    /**
     * 用当前服务器时间渲染测试邮件 HTML。
     *
     * @return 可发送的完整 HTML 字符串
     */
    public static String render() {
        return render(LocalDateTime.now());
    }

    /**
     * 用指定时间渲染测试邮件 HTML。
     *
     * @param serverTime 服务器当前时间
     * @return 可发送的完整 HTML 字符串
     */
    public static String render(LocalDateTime serverTime) {
        String html = template;
        if (html == null) {
            synchronized (TestMailTemplate.class) {
                html = template;
                if (html == null) {
                    html = loadTemplate();
                    template = html;
                }
            }
        }
        return html.replace(PLACEHOLDER_SERVER_TIME, serverTime.format(TIME_FORMAT));
    }

    private static String loadTemplate() {
        try (InputStream in = TestMailTemplate.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("测试邮件模板资源缺失: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取测试邮件模板失败: " + TEMPLATE_RESOURCE, ex);
        }
    }
}
