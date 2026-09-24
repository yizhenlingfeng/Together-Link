package mchorse.bbs_together.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mchorse.bbs_mod.BBSCommands;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={BBSCommands.class}, remap=false)
public class BBSCommandsMixin {
    @Inject(method={"register"}, at={@At(value="TAIL")}, remap=false)
    private static void onRegister(CommandDispatcher<ServerCommandSource> commandDispatcher, CommandRegistryAccess class_71572, CommandManager.RegistrationEnvironment class_53642, CallbackInfo callbackInfo) {
        commandDispatcher.register((LiteralArgumentBuilder)CommandManager.literal((String)"bbstogether").then(((LiteralArgumentBuilder)CommandManager.literal((String)"upload").requires(class_21682 -> class_21682.hasPermissionLevel(2))).executes(BBSCommandsMixin::together_updateAssets)));
    }

    private static int together_updateAssets(CommandContext<ServerCommandSource> commandContext) throws CommandSyntaxException {
        ServerCommandSource class_21682 = (ServerCommandSource)commandContext.getSource();
        ServerPlayerEntity class_32222 = class_21682.getPlayerOrThrow();
        MinecraftServer minecraftServer = class_21682.getServer();
        TogetherServerNetwork.sendAssetManifest(minecraftServer, class_32222);
        for (ServerPlayerEntity class_32223 : minecraftServer.getPlayerManager().getPlayerList()) {
            if (class_32223 == class_32222) continue;
            TogetherServerNetwork.sendAssetManifest(minecraftServer, class_32223);
        }
        class_21682.sendFeedback(() -> Text.literal((String)"BBSTogether: requested asset upload and server asset refresh."), false);
        return 1;
    }
}

