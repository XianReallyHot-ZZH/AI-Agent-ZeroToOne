package com.example.agent.team;

import com.example.agent.util.Console;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSONL 邮箱消息总线：基于文件的 Agent 间通信。
 * <p>
 * 每个 teammate 拥有独立的 JSONL 文件作为收件箱：
 * <pre>
 * .team/inbox/
 *   alice.jsonl    ← alice 的收件箱
 *   bob.jsonl      ← bob 的收件箱
 *   lead.jsonl     ← 主 Agent 的收件箱
 * </pre>
 * <p>
 * 并发安全：每个 inbox 文件一把独立的 {@link ReentrantLock}，
 * 使用 {@link ConcurrentHashMap} 管理锁池。
 * <p>
 * 对应 Python 原版：s09_agent_teams.py 中的 MessageBus 类。
 */
public class MessageBus {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 有效消息类型 */
    public static final Set<String> VALID_MSG_TYPES = Set.of(
            "message", "broadcast", "shutdown_request",
            "shutdown_response", "plan_approval_response"
    );

    /** 收件箱目录 */
    private final Path inboxDir;

    /** 每个收件箱文件的独立锁（防止并发写入数据竞争） */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public MessageBus(Path inboxDir) {
        this.inboxDir = inboxDir;
        try {
            Files.createDirectories(inboxDir);
        } catch (IOException e) {
            System.out.println(Console.red("创建 inbox 目录失败: " + e.getMessage()));
        }
    }

    /**
     * 发送消息到指定 teammate 的收件箱。
     * <p>
     * 对应 Python: MessageBus.send(sender, to, content, msg_type, extra)
     * 使用 append-only 写入，一行一条 JSON 消息。
     *
     * @param sender  发送者名称
     * @param to      接收者名称
     * @param content 消息内容
     * @param msgType 消息类型
     * @param extra   附加字段（可为 null）
     * @return 操作确认
     */
    public String send(String sender, String to, String content,
                       String msgType, Map<String, Object> extra) {
        if (!VALID_MSG_TYPES.contains(msgType)) {
            return "Error: Invalid type '" + msgType + "'. Valid: " + VALID_MSG_TYPES;
        }

        var msg = new LinkedHashMap<String, Object>();
        msg.put("type", msgType);
        msg.put("from", sender);
        msg.put("content", content);
        msg.put("timestamp", System.currentTimeMillis() / 1000.0);
        if (extra != null) {
            msg.putAll(extra);
        }

        // 获取该收件箱的锁
        ReentrantLock lock = locks.computeIfAbsent(to, k -> new ReentrantLock());
        lock.lock();
        try {
            Path inboxPath = inboxDir.resolve(to + ".jsonl");
            String line = MAPPER.writeValueAsString(msg) + "\n";
            Files.writeString(inboxPath, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "Sent " + msgType + " to " + to;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取并清空收件箱（drain 语义）。
     * <p>
     * 对应 Python: MessageBus.read_inbox(name)
     *
     * @param name teammate 名称
     * @return 消息列表（读取后收件箱被清空）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readInbox(String name) {
        Path inboxPath = inboxDir.resolve(name + ".jsonl");
        if (!Files.exists(inboxPath)) {
            return List.of();
        }

        ReentrantLock lock = locks.computeIfAbsent(name, k -> new ReentrantLock());
        lock.lock();
        try {
            String text = Files.readString(inboxPath).trim();
            if (text.isEmpty()) {
                return List.of();
            }

            var messages = new ArrayList<Map<String, Object>>();
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    messages.add(MAPPER.readValue(line, Map.class));
                }
            }

            // 清空收件箱
            Files.writeString(inboxPath, "");
            return messages;
        } catch (IOException e) {
            System.out.println(Console.red("读取收件箱失败: " + e.getMessage()));
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 广播消息给所有 teammate（排除发送者自身）。
     * <p>
     * 对应 Python: MessageBus.broadcast(sender, content, teammates)
     */
    public String broadcast(String sender, String content, List<String> teammates) {
        int count = 0;
        for (String name : teammates) {
            if (!name.equals(sender)) {
                send(sender, name, content, "broadcast", null);
                count++;
            }
        }
        return "Broadcast to " + count + " teammates";
    }
}
