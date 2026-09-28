package com.siva.storybot.service;

import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;

import java.util.List;

@Slf4j
@Service
public class TelegramRoleCommandRegistrar {

    public List<BotCommand> getUserCommands() {
        return List.of(
                new BotCommand("start", "Open Story Bot"),
                new BotCommand("panel", "Open your menu"),
                new BotCommand("help", "Help and support")
        );
    }

    public List<BotCommand> getAdminCommands() {
        return List.of(
                new BotCommand("start", "Open admin panel"),
                new BotCommand("panel", "Open admin panel"),
                new BotCommand("storyaccess", "Manage USER story access"),
                new BotCommand("addstoryicon", "Add or replace story icon"),
                new BotCommand("removestoryicon", "Remove story icon"),
                new BotCommand("usage", "View admin command examples"),
                new BotCommand("help", "View admin help")
        );
    }

    public List<BotCommand> getOwnerCommands() {
        return List.of(
                new BotCommand("start", "Open owner panel"),
                new BotCommand("panel", "Open owner panel"),
                new BotCommand("users", "View users"),
                new BotCommand("userdetails", "View one user details"),
                new BotCommand("approveadmin", "Promote USER to ADMIN"),
                new BotCommand("disapproveadmin", "Change ADMIN back to USER"),
                new BotCommand("storyaccess", "Manage story access"),
                new BotCommand("stories", "View owner story library"),
                new BotCommand("syncstories", "Sync story channels"),
                new BotCommand("deleteinactivestory", "Delete inactive stories"),
                new BotCommand("addstoryicon", "Add or replace story icon"),
                new BotCommand("removestoryicon", "Remove story icon"),
                new BotCommand("usage", "View owner command examples"),
                new BotCommand("help", "View owner help")
        );
    }

    public void registerDefaultCommands(TelegramLongPollingBot bot) throws Exception {
        register(bot, getUserCommands(), new BotCommandScopeDefault());
    }

    public void registerOwnerCommands(TelegramLongPollingBot bot, Long chatId) throws Exception {
        if (chatId == null) throw new IllegalArgumentException("OWNER chatId cannot be null");
        register(bot, getOwnerCommands(), new BotCommandScopeChat(String.valueOf(chatId)));
    }

    public void registerAdminCommands(TelegramLongPollingBot bot, Long chatId) throws Exception {
        if (chatId == null) throw new IllegalArgumentException("ADMIN chatId cannot be null");
        register(bot, getAdminCommands(), new BotCommandScopeChat(String.valueOf(chatId)));
    }

    public void registerUserCommands(TelegramLongPollingBot bot, Long chatId) throws Exception {
        if (chatId == null) throw new IllegalArgumentException("USER chatId cannot be null");
        register(bot, getUserCommands(), new BotCommandScopeChat(String.valueOf(chatId)));
    }

    public void syncCommandsForUser(TelegramLongPollingBot bot, TelegramUser user) {
        if (bot == null || user == null || user.getRole() == null) return;
        Long chatId = user.getChatId() != null ? user.getChatId() : user.getTelegramId();
        if (chatId == null) return;

        try {
            if (user.getRole() == UserRole.OWNER) {
                registerOwnerCommands(bot, chatId);
            } else if (user.getRole() == UserRole.ADMIN) {
                registerAdminCommands(bot, chatId);
            } else {
                registerUserCommands(bot, chatId);
            }
        } catch (Exception e) {
            log.warn("Unable to sync command menu telegramId={} role={} reason={}",
                    user.getTelegramId(), user.getRole(), e.getMessage());
        }
    }

    private void register(TelegramLongPollingBot bot, List<BotCommand> commands, Object scope) throws Exception {
        SetMyCommands request = new SetMyCommands();
        request.setCommands(commands);
        if (scope instanceof BotCommandScopeDefault defaultScope) {
            request.setScope(defaultScope);
        } else if (scope instanceof BotCommandScopeChat chatScope) {
            request.setScope(chatScope);
        }
        bot.execute(request);
    }
}
