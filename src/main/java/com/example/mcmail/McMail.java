package com.example.mcmail;

import com.example.mcmail.command.McMailCommand;
import com.example.mcmail.config.McMailConfig;
import com.example.mcmail.mail.MailService;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * McMail 主入口：仅在专用服务端（dedicated server）生效的“邮件配置桥接” Mod。
 * <p>
 * 注意：Forge 1.20.1 的 {@code @Mod} 注解没有 {@code dist} 属性，主类在物理客户端
 * 也会被 FML 加载；因此在构造器中用 {@link FMLEnvironment#dist} 守卫：非专用服务器
 * 环境下不注册配置、不注册任何事件（功能完全不存在）。本类不引用任何
 * {@code net.minecraft.client.*} 类；普通玩家没有任何可用入口，全部功能仅面向
 * 权限等级 4 的 OP / 控制台，以及其他 Mod 通过 {@code MailApi} 的代码级调用。
 */
@Mod(McMail.MODID)
public final class McMail {

    /** Mod ID，与 gradle.properties、mods.toml 保持一致。 */
    public static final String MODID = "mcmail";

    private static final Logger LOGGER = LogUtils.getLogger();

    public McMail(FMLJavaModLoadingContext context) {
        // 物理客户端（含单人集成服务器环境）直接跳过：不注册配置/事件，相当于 mod 不生效。
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }

        // 注册 COMMON 配置 -> config/mcmail-common.toml
        context.registerConfig(ModConfig.Type.COMMON, McMailConfig.SPEC, McMailConfig.FILE_NAME);

        // 注册 Forge 事件实例监听（生命周期 + 命令注册）
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[McMail] Loaded (dedicated-server only)");
    }

    /** 服务器启动完成：初始化邮件线程池。 */
    @SubscribeEvent
    public void onServerStarted(final ServerStartedEvent event) {
        MailService.getInstance().init();
    }

    /** 服务器停止中：关闭邮件线程池，避免在途线程泄漏。 */
    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event) {
        MailService.getInstance().shutdown();
    }

    /** 注册 /mcmail 命令树（命令内部统一要求权限等级 4）。 */
    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        McMailCommand.register(event.getDispatcher());
    }
}
