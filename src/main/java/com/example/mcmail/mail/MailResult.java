package com.example.mcmail.mail;

/**
 * 邮件操作结果。所有网络/认证异常都在异步线程内被转换成本对象，
 * 绝不向服务器主线程抛出。
 *
 * @param success 是否成功
 * @param message 面向管理员的中文描述（成功/失败原因），命令回显时会加上 {@code [McMail]} 前缀
 */
public record MailResult(boolean success, String message) {

    public static MailResult success(String message) {
        return new MailResult(true, message);
    }

    public static MailResult fail(String message) {
        return new MailResult(false, message);
    }
}
