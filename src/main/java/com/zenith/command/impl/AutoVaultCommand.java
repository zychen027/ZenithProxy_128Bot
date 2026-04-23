package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;

import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class AutoVaultCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
                .name("autoVault")
                .category(CommandCategory.MODULE)
                .description("Automatically uses trial keys on nearby vaults.")
                .usageLines(
                        "on/off"
                )
                .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoVault")
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.client.extra.autoVault.enabled = getToggle(c, "toggle");
                    MODULE.get(com.zenith.module.impl.AutoVault.class).syncEnabledFromConfig();
                    c.getSource().getEmbed()
                            .title("AutoVault " + toggleStrCaps(CONFIG.client.extra.autoVault.enabled));
                    return OK;
                }));
    }

    @Override
    public void defaultHandler(final CommandContext context) {
        context.getEmbed()
                .addField("AutoVault", toggleStr(CONFIG.client.extra.autoVault.enabled))
                .primaryColor();
    }
}
