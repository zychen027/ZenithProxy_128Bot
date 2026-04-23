package com.zenith.command.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;

import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class Auto128BotCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
                .name("auto128Bot")
                .category(CommandCategory.MODULE)
                .description("Automatically switches between bot accounts to farm vaults.")
                .usageLines(
                        "on/off",
                        "prefix <string>",
                        "password <string>"
                )
                .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("auto128Bot")
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.client.extra.auto128Bot.enabled = getToggle(c, "toggle");
                    MODULE.get(com.zenith.module.impl.Auto128Bot.class).syncEnabledFromConfig();
                    c.getSource().getEmbed()
                            .title("Auto128Bot " + toggleStrCaps(CONFIG.client.extra.auto128Bot.enabled));
                    return OK;
                }))
                .then(literal("prefix").then(argument("prefix", StringArgumentType.word()).executes(c -> {
                    CONFIG.client.extra.auto128Bot.prefix = getString(c, "prefix");
                    c.getSource().getEmbed()
                            .title("Prefix Set")
                            .description("Bot prefix is now: " + CONFIG.client.extra.auto128Bot.prefix);
                    return OK;
                })))
                .then(literal("password").then(argument("password", StringArgumentType.word()).executes(c -> {
                    CONFIG.client.extra.auto128Bot.password = getString(c, "password");
                    c.getSource().getEmbed()
                            .title("Password Set");
                    return OK;
                })));
    }

    @Override
    public void defaultHandler(final CommandContext context) {
        context.getEmbed()
                .addField("Auto128Bot", toggleStr(CONFIG.client.extra.auto128Bot.enabled))
                .addField("Prefix", CONFIG.client.extra.auto128Bot.prefix)
                .primaryColor();
    }
}
