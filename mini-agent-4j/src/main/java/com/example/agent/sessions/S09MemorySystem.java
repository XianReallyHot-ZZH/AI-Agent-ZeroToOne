package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S09：记忆系统 —— 完全自包含实现（不依赖 core/、tools/、util/ 包）。
 * <p>
 * 本课聚焦一个核心理念：有些信息应当跨越会话边界存活，但不是所有东西都值得记忆。
 * <p>
 * 应该记忆的：
 *   - 用户偏好（"我喜欢 tabs"、"始终用 pytest"）
 *   - 用户反复给出的反馈（"别做 X"、"那样做是错的因为..."）
 *   - 从当前代码不易推断出的项目事实（如合规原因、遗留模块不可碰）
 *   - 外部资源指针（看板地址、文档 URL）
 * <p>
 * 不应该记忆的：
 *   - 可以从代码重新推导出的结构信息
 *   - 临时任务状态（当前分支、打开的 PR）
 *   - 密钥或凭据
 * <p>
 * 存储布局：
 * <pre>
 *   .memory/
 *     MEMORY.md          ← 索引文件（200 行上限）
 *     prefer_tabs.md     ← 单条记忆（frontmatter + 正文）
 *     review_style.md
 *     incident_board.md
 * </pre>
 * <p>
 * 关键洞察："记忆只存储：跨会话仍有召回价值，且不易从仓库重新推导的信息。"
 * <p>
 * 本文件将所有基础设施内联：
 * - MemoryManager（内部类）：持久记忆管理，frontmatter 存储格式
 * - DreamConsolidator（内部类）：可选的记忆自动整理（7 道门 + 4 阶段）
 * - buildClient()：构建 Anthropic API 客户端
 * - loadModel()：从环境变量加载模型 ID
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令
 * - runRead()：读取文件内容
 * - runWrite()：写入文件
 * - runEdit()：精确文本替换
 * - safePath()：路径沙箱校验
 * - ANSI 输出：终端彩色文本
 * - agentLoop()：核心 LLM 调用 → 工具执行 → 结果回传循环
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * <p>
 * 对应 Python 原版：s09_memory_system.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s09_memory_system.py">Python 原版</a>
 */
public class S09MemorySystem {

    // ==================== 常量 ====================

    /** 最大输出长度（字符），与 Python 原版 50000 对齐 */
    private static final int MAX_OUTPUT = 50000;

    /** bash 命令超时（秒），与 Python 原版 120s 对齐 */
    private static final int BASH_TIMEOUT = 120;

    /** 危险命令黑名单，防止模型执行破坏性操作 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 工作目录（Agent 的文件操作沙箱根目录） */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** 记忆存储目录 */
    private static final Path MEMORY_DIR = WORK_DIR.resolve(".memory");

    /** 记忆索引文件 */
    private static final Path MEMORY_INDEX = MEMORY_DIR.resolve("MEMORY.md");

    /** 合法的记忆类型 */
    private static final List<String> MEMORY_TYPES = List.of("user", "feedback", "project", "reference");

    /** 索引文件最大行数 */
    private static final int MAX_INDEX_LINES = 200;

    /** 记忆使用指南：注入系统提示词，告诉模型何时该/不该保存记忆 */
    private static final String MEMORY_GUIDANCE = """

            When to save memories:
            - User states a preference ("I like tabs", "always use pytest") -> type: user
            - User corrects you ("don't do X", "that was wrong because...") -> type: feedback
            - You learn a project fact that is not easy to infer from current code alone
              (for example: a rule exists because of compliance, or a legacy module must
              stay untouched for business reasons) -> type: project
            - You learn where an external resource lives (ticket board, dashboard, docs URL)
              -> type: reference

            When NOT to save:
            - Anything easily derivable from code (function signatures, file structure, directory layout)
            - Temporary task state (current branch, open PR numbers, current TODOs)
            - Secrets or credentials (API keys, passwords)
            """;

    // ==================== ANSI 颜色输出 ====================
    // 终端彩色文本，让 REPL 和工具日志更易读

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";

    /** 检测终端是否支持 ANSI 转义码 */
    private static final boolean ANSI_SUPPORTED = detectAnsi();

    private static boolean detectAnsi() {
        // Unix 终端通常通过 TERM 环境变量标识
        String term = System.getenv("TERM");
        if (term != null && !term.isEmpty()) return true;
        // Windows Terminal 会设置 WT_SESSION
        if (System.getenv("WT_SESSION") != null) return true;
        // ConEmu 终端
        if ("ON".equalsIgnoreCase(System.getenv("ConEmuANSI"))) return true;
        // 现代 Windows 10+ 终端通常也支持
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** 应用 ANSI 颜色，不支持时原样返回 */
    private static String ansi(String code, String text) {
        return ANSI_SUPPORTED ? code + text + ANSI_RESET : text;
    }

    private static String bold(String text) { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)  { return ansi(ANSI_DIM, text); }
    private static String cyan(String text) { return ansi(ANSI_CYAN, text); }
    private static String red(String text)  { return ansi(ANSI_RED, text); }

    // ==================== 内部类：MemoryManager ====================

    /**
     * 持久记忆管理器。
     * <p>
     * 教学版保持记忆显式可见：一条记忆 = 一个 Markdown 文件 + 一个紧凑的索引文件。
     * <p>
     * 职责：
     * - loadAll()：扫描 .memory/*.md，解析 frontmatter，加载到内存
     * - loadMemoryPrompt()：构建 Markdown 片段注入系统提示词
     * - saveMemory()：写入 frontmatter 文件，重建索引
     * - _parseFrontmatter()：解析 --- 分隔的 frontmatter 头部
     * - _rebuildIndex()：从内存状态重建 MEMORY.md 索引（200 行上限）
     */
    static class MemoryManager {

        /** 记忆存储目录 */
        private final Path memoryDir;

        /** 内存中的记忆映射：name -> {description, type, content, file} */
        private final Map<String, Map<String, String>> memories = new LinkedHashMap<>();

        /**
         * 构造记忆管理器。
         *
         * @param memoryDir 记忆目录路径，null 则使用默认 MEMORY_DIR
         */
        MemoryManager(Path memoryDir) {
            this.memoryDir = memoryDir != null ? memoryDir : MEMORY_DIR;
        }

        /**
         * 加载所有记忆。
         * <p>
         * 扫描 .memory/ 下所有 .md 文件（MEMORY.md 索引文件除外），
         * 解析每条记忆的 frontmatter，填充到内存映射中。
         * <p>
         * 对应 Python 原版：MemoryManager.load_all()
         */
        void loadAll() {
            memories.clear();

            if (!Files.exists(memoryDir)) {
                return;
            }

            // 扫描所有 .md 文件，跳过 MEMORY.md 索引
            try (var stream = Files.list(memoryDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                      .sorted()
                      .forEach(mdFile -> {
                          try {
                              String text = Files.readString(mdFile);
                              Map<String, String> parsed = parseFrontmatter(text);
                              if (parsed != null) {
                                  String name = parsed.getOrDefault("name",
                                          mdFile.getFileName().toString().replace(".md", ""));
                                  Map<String, String> entry = new LinkedHashMap<>();
                                  entry.put("description", parsed.getOrDefault("description", ""));
                                  entry.put("type", parsed.getOrDefault("type", "project"));
                                  entry.put("content", parsed.getOrDefault("content", ""));
                                  entry.put("file", mdFile.getFileName().toString());
                                  memories.put(name, entry);
                              }
                          } catch (Exception e) {
                              // 跳过无法解析的文件
                          }
                      });
            } catch (Exception e) {
                // 目录不存在或无法读取，静默处理
            }

            int count = memories.size();
            if (count > 0) {
                System.out.println("[Memory loaded: " + count + " memories from " + memoryDir + "]");
            }
        }

        /**
         * 构建记忆 Markdown 片段，用于注入系统提示词。
         * <p>
         * 按记忆类型分组，生成结构化的 Markdown 输出。
         * 如果没有记忆则返回空字符串。
         * <p>
         * 对应 Python 原版：MemoryManager.load_memory_prompt()
         *
         * @return 注入系统提示词的 Markdown 片段，无记忆时返回空字符串
         */
        String loadMemoryPrompt() {
            if (memories.isEmpty()) {
                return "";
            }

            List<String> sections = new ArrayList<>();
            sections.add("# Memories (persistent across sessions)");
            sections.add("");

            // 按类型分组输出，提升可读性
            for (String memType : MEMORY_TYPES) {
                // 筛选当前类型的记忆
                Map<String, Map<String, String>> typed = new LinkedHashMap<>();
                for (var entry : memories.entrySet()) {
                    if (memType.equals(entry.getValue().get("type"))) {
                        typed.put(entry.getKey(), entry.getValue());
                    }
                }
                if (typed.isEmpty()) continue;

                sections.add("## [" + memType + "]");
                for (var entry : typed.entrySet()) {
                    String name = entry.getKey();
                    Map<String, String> mem = entry.getValue();
                    sections.add("### " + name + ": " + mem.get("description"));
                    String content = mem.get("content");
                    if (content != null && !content.isBlank()) {
                        sections.add(content.strip());
                    }
                    sections.add("");
                }
            }

            return String.join("\n", sections);
        }

        /**
         * 保存一条记忆到磁盘，并更新索引。
         * <p>
         * 写入流程：
         * 1. 校验记忆类型合法性
         * 2. 清理名称生成安全文件名
         * 3. 创建 .memory 目录（如果不存在）
         * 4. 写入 frontmatter 格式的 .md 文件
         * 5. 更新内存映射
         * 6. 重建 MEMORY.md 索引
         * <p>
         * 对应 Python 原版：MemoryManager.save_memory()
         *
         * @param name        记忆标识名（如 prefer_tabs, db_schema）
         * @param description 一句话摘要
         * @param memType     记忆类型：user / feedback / project / reference
         * @param content     完整记忆内容（支持多行）
         * @return 操作结果描述
         */
        String saveMemory(String name, String description, String memType, String content) {
            // 校验类型合法性
            if (!MEMORY_TYPES.contains(memType)) {
                return "Error: type must be one of " + MEMORY_TYPES;
            }

            // 清理名称，生成安全文件名（只保留字母、数字、下划线、横杠）
            String safeName = name.toLowerCase().replaceAll("[^a-zA-Z0-9_-]", "_");
            if (safeName.isEmpty()) {
                return "Error: invalid memory name";
            }

            try {
                // 确保记忆目录存在
                Files.createDirectories(memoryDir);

                // 构建 frontmatter 格式
                String frontmatter = "---\n"
                        + "name: " + name + "\n"
                        + "description: " + description + "\n"
                        + "type: " + memType + "\n"
                        + "---\n"
                        + content + "\n";

                // 写入记忆文件
                String fileName = safeName + ".md";
                Path filePath = memoryDir.resolve(fileName);
                Files.writeString(filePath, frontmatter);

                // 更新内存映射
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("description", description);
                entry.put("type", memType);
                entry.put("content", content);
                entry.put("file", fileName);
                memories.put(name, entry);

                // 重建 MEMORY.md 索引
                rebuildIndex();

                // 计算相对路径用于输出
                Path relativePath = WORK_DIR.relativize(filePath);
                return "Saved memory '" + name + "' [" + memType + "] to " + relativePath;

            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        /**
         * 重建 MEMORY.md 索引文件。
         * <p>
         * 从当前内存状态生成索引，限制在 200 行以内。
         * 索引格式：每条记忆一行摘要。
         * <p>
         * 对应 Python 原版：MemoryManager._rebuild_index()
         */
        private void rebuildIndex() {
            try {
                List<String> lines = new ArrayList<>();
                lines.add("# Memory Index");
                lines.add("");

                for (var entry : memories.entrySet()) {
                    String name = entry.getKey();
                    Map<String, String> mem = entry.getValue();
                    lines.add("- " + name + ": " + mem.get("description") + " [" + mem.get("type") + "]");
                    // 行数上限保护
                    if (lines.size() >= MAX_INDEX_LINES) {
                        lines.add("... (truncated at " + MAX_INDEX_LINES + " lines)");
                        break;
                    }
                }

                Files.createDirectories(memoryDir);
                Files.writeString(MEMORY_INDEX, String.join("\n", lines) + "\n");
            } catch (Exception e) {
                // 索引写入失败不阻塞主流程
            }
        }

        /**
         * 解析 frontmatter 格式的文本。
         * <p>
         * frontmatter 格式：
         * <pre>
         * ---
         * key1: value1
         * key2: value2
         * ---
         * 正文内容（多行）
         * </pre>
         * <p>
         * 对应 Python 原版：MemoryManager._parse_frontmatter()
         *
         * @param text 待解析的文本
         * @return 解析结果 Map（包含 frontmatter 字段和 content 正文），解析失败返回 null
         */
        Map<String, String> parseFrontmatter(String text) {
            if (text == null || text.isEmpty()) return null;

            // 匹配 --- 开头和结尾的 frontmatter 块
            Pattern pattern = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) return null;

            String header = matcher.group(1);
            String body = matcher.group(2);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("content", body.strip());

            // 逐行解析 frontmatter 键值对
            for (String line : header.split("\n")) {
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).strip();
                    String value = line.substring(colonIndex + 1).strip();
                    result.put(key, value);
                }
            }
            return result;
        }

        /**
         * 获取当前记忆数量。
         *
         * @return 记忆条数
         */
        int size() {
            return memories.size();
        }

        /**
         * 获取所有记忆的快照（只读视图）。
         *
         * @return 不可修改的记忆映射
         */
        Map<String, Map<String, String>> getMemories() {
            return Collections.unmodifiableMap(memories);
        }
    }

    // ==================== 内部类：DreamConsolidator ====================

    /**
     * 记忆自动整理器（"Dream" 整理阶段）。
     * <p>
     * 这是可选的后阶段特性。其职责是防止记忆堆积为噪音，
     * 通过合并、去重和修剪条目来维持记忆质量。
     * <p>
     * 教学版：7 道门检查 + 4 阶段处理流程。
     * consolidate() 仅打印阶段日志，不做实际的 LLM 整理。
     * <p>
     * 七道门（全部通过才执行整理）：
     * 1. enabled 标志检查
     * 2. 记忆目录存在性检查
     * 3. 非 plan 模式检查
     * 4. 24 小时冷却期检查
     * 5. 10 分钟扫描节流检查
     * 6. 最少会话数检查（5 次）
     * 7. PID 锁检查（防止并发整理）
     * <p>
     * 四个阶段：
     * 1. Orient：扫描索引结构
     * 2. Gather：读取完整记忆内容
     * 3. Consolidate：合并相关记忆，移除过期条目
     * 4. Prune：强制执行 200 行索引上限
     * <p>
     * 对应 Python 原版：DreamConsolidator 类
     */
    static class DreamConsolidator {

        /** 整理冷却期（毫秒）：24 小时 */
        private static final long COOLDOWN_MS = 86400_000L;

        /** 扫描节流间隔（毫秒）：10 分钟 */
        private static final long SCAN_THROTTLE_MS = 600_000L;

        /** 最少会话数：需要足够数据才能整理 */
        private static final int MIN_SESSION_COUNT = 5;

        /** PID 锁过期时间（毫秒）：1 小时 */
        private static final long LOCK_STALE_MS = 3600_000L;

        /** 四个阶段的描述 */
        private static final List<String> PHASES = List.of(
                "Orient: scan MEMORY.md index for structure and categories",
                "Gather: read individual memory files for full content",
                "Consolidate: merge related memories, remove stale entries",
                "Prune: enforce 200-line limit on MEMORY.md index"
        );

        /** 记忆目录 */
        private final Path memoryDir;

        /** PID 锁文件 */
        private final Path lockFile;

        /** 是否启用整理 */
        boolean enabled = true;

        /** 当前模式（plan 模式下不整理） */
        String mode = "default";

        /** 上次整理时间戳（毫秒） */
        long lastConsolidationTime = 0L;

        /** 上次扫描时间戳（毫秒） */
        long lastScanTime = 0L;

        /** 会话计数 */
        int sessionCount = 0;

        /**
         * 构造整理器。
         *
         * @param memoryDir 记忆目录路径，null 则使用默认 MEMORY_DIR
         */
        DreamConsolidator(Path memoryDir) {
            this.memoryDir = memoryDir != null ? memoryDir : MEMORY_DIR;
            this.lockFile = this.memoryDir.resolve(".dream_lock");
        }

        /**
         * 7 道门检查。
         * <p>
         * 按顺序检查所有门禁条件，第一道失败立即返回原因。
         * 所有门通过后才允许执行整理。
         * <p>
         * 对应 Python 原版：DreamConsolidator.should_consolidate()
         *
         * @return 二元组 [是否可执行, 原因说明]
         */
        String[] shouldConsolidate() {
            long now = System.currentTimeMillis();

            // 门 1：enabled 标志
            if (!enabled) {
                return new String[]{"false", "Gate 1: consolidation is disabled"};
            }

            // 门 2：记忆目录存在且有记忆文件
            if (!Files.exists(memoryDir)) {
                return new String[]{"false", "Gate 2: memory directory does not exist"};
            }
            try (var stream = Files.list(memoryDir)) {
                long fileCount = stream
                        .filter(p -> p.toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                        .count();
                if (fileCount == 0) {
                    return new String[]{"false", "Gate 2: no memory files found"};
                }
            } catch (Exception e) {
                return new String[]{"false", "Gate 2: cannot read memory directory"};
            }

            // 门 3：非 plan 模式
            if ("plan".equals(mode)) {
                return new String[]{"false", "Gate 3: plan mode does not allow consolidation"};
            }

            // 门 4：24 小时冷却期
            long timeSinceLast = now - lastConsolidationTime;
            if (timeSinceLast < COOLDOWN_MS) {
                long remaining = (COOLDOWN_MS - timeSinceLast) / 1000;
                return new String[]{"false", "Gate 4: cooldown active, " + remaining + "s remaining"};
            }

            // 门 5：10 分钟扫描节流
            long timeSinceScan = now - lastScanTime;
            if (timeSinceScan < SCAN_THROTTLE_MS) {
                long remaining = (SCAN_THROTTLE_MS - timeSinceScan) / 1000;
                return new String[]{"false", "Gate 5: scan throttle active, " + remaining + "s remaining"};
            }

            // 门 6：最少会话数
            if (sessionCount < MIN_SESSION_COUNT) {
                return new String[]{"false", "Gate 6: only " + sessionCount + " sessions, need " + MIN_SESSION_COUNT};
            }

            // 门 7：PID 锁（防止并发整理）
            if (!acquireLock()) {
                return new String[]{"false", "Gate 7: lock held by another process"};
            }

            return new String[]{"true", "All 7 gates passed"};
        }

        /**
         * 执行 4 阶段整理流程。
         * <p>
         * 教学版仅打印阶段日志，让流程可见，
         * 不做实际的 LLM 整理调用。
         * <p>
         * 对应 Python 原版：DreamConsolidator.consolidate()
         *
         * @return 已完成的阶段描述列表
         */
        List<String> consolidate() {
            String[] check = shouldConsolidate();
            boolean canRun = "true".equals(check[0]);
            String reason = check[1];

            if (!canRun) {
                System.out.println("[Dream] Cannot consolidate: " + reason);
                return List.of();
            }

            System.out.println("[Dream] Starting consolidation...");
            lastScanTime = System.currentTimeMillis();

            List<String> completedPhases = new ArrayList<>();
            for (int i = 0; i < PHASES.size(); i++) {
                String phase = PHASES.get(i);
                System.out.println("[Dream] Phase " + (i + 1) + "/4: " + phase);
                completedPhases.add(phase);
            }

            lastConsolidationTime = System.currentTimeMillis();
            releaseLock();
            System.out.println("[Dream] Consolidation complete: " + completedPhases.size() + " phases executed");
            return completedPhases;
        }

        /**
         * 获取 PID 锁。
         * <p>
         * 如果锁文件存在，检查持有进程是否仍然存活。
         * 过期锁（超过 1 小时）自动清除。
         * 对应 Python 原版：os.kill(pid, 0) 检测进程存活。
         * <p>
         * 对应 Python 原版：DreamConsolidator._acquire_lock()
         *
         * @return true 表示成功获取锁，false 表示锁被其他进程持有
         */
        boolean acquireLock() {
            if (Files.exists(lockFile)) {
                try {
                    String lockData = Files.readString(lockFile).strip();
                    String[] parts = lockData.split(":", 2);
                    int pid = Integer.parseInt(parts[0]);
                    long lockTime = Long.parseLong(parts[1]);

                    // 检查锁是否过期
                    if ((System.currentTimeMillis() - lockTime) > LOCK_STALE_MS) {
                        System.out.println("[Dream] Removing stale lock from PID " + pid);
                        Files.deleteIfExists(lockFile);
                    } else {
                        // 检查持有进程是否仍存活
                        // Java 中通过 ProcessHandle 检测（Java 9+）
                        try {
                            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                            if (handle != null && handle.isAlive()) {
                                return false; // 进程存活，锁有效
                            }
                            // 进程已死，清除锁
                            System.out.println("[Dream] Removing lock from dead PID " + pid);
                            Files.deleteIfExists(lockFile);
                        } catch (Exception e) {
                            // 无法检测进程状态，保守起见不获取锁
                            return false;
                        }
                    }
                } catch (Exception e) {
                    // 锁文件损坏，清除
                    try { Files.deleteIfExists(lockFile); } catch (Exception ignored) {}
                }
            }

            // 写入新锁
            try {
                Files.createDirectories(memoryDir);
                String lockContent = ProcessHandle.current().pid() + ":" + System.currentTimeMillis();
                Files.writeString(lockFile, lockContent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 释放 PID 锁。
         * <p>
         * 只有当锁文件中的 PID 与当前进程匹配时才删除，
         * 防止误删其他进程的锁。
         * <p>
         * 对应 Python 原版：DreamConsolidator._release_lock()
         */
        void releaseLock() {
            try {
                if (Files.exists(lockFile)) {
                    String lockData = Files.readString(lockFile).strip();
                    String pidStr = lockData.split(":")[0];
                    long lockPid = Long.parseLong(pidStr);
                    if (lockPid == ProcessHandle.current().pid()) {
                        Files.deleteIfExists(lockFile);
                    }
                }
            } catch (Exception e) {
                // 释放失败不影响主流程
            }
        }
    }

    // ==================== 全局记忆管理器实例 ====================

    /** 全局记忆管理器，对应 Python 原版的 memory_mgr 全局变量 */
    private static final MemoryManager memoryMgr = new MemoryManager(MEMORY_DIR);

    /** 全局 Dream 整理器，对应 Python 原版的 DreamConsolidator 实例 */
    private static final DreamConsolidator dreamConsolidator = new DreamConsolidator(MEMORY_DIR);

    // ==================== 环境变量 & 客户端构建 ====================

    /**
     * 加载 .env 文件并返回统一的环境变量读取接口。
     * <p>
     * 优先读取 .env 文件，若不存在则回退到系统环境变量。
     * 对应 Python 原版顶部的 load_dotenv(override=True)。
     */
    private static Dotenv loadDotenv() {
        return new DotenvBuilder()
                .ignoreIfMissing()    // .env 不存在时不报错
                .systemProperties()   // 同时读取系统属性
                .load();
    }

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter）。
     * 如果设置了 ANTHROPIC_BASE_URL，则清除 ANTHROPIC_AUTH_TOKEN 避免冲突
     * （与 Python 原版行为对齐）。
     */
    private static AnthropicClient buildClient() {
        Dotenv dotenv = loadDotenv();

        // 如果设置了自定义 baseUrl，清除 auth token 避免冲突
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY 未配置。请在 .env 文件或系统环境变量中设置。");
        }

        if (baseUrl != null && !baseUrl.isBlank()) {
            return AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
        }
        // 使用默认 Anthropic 端点
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    /**
     * 从环境变量加载模型 ID。
     * <p>
     * 对应 Python 原版的 MODEL = os.environ["MODEL_ID"]。
     */
    private static String loadModel() {
        Dotenv dotenv = loadDotenv();
        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }
        return model;
    }

    // ==================== 工具定义辅助 ====================

    /**
     * 构建一个 SDK Tool 定义。
     * <p>
     * 将简单的 name/description/properties/required 参数转换为 Anthropic SDK 的 Tool 对象。
     * 这是所有工具定义的统一入口。
     *
     * @param name        工具名称（模型调用时使用）
     * @param description 工具描述（告诉模型工具的用途）
     * @param properties  JSON Schema 属性定义
     * @param required    必需属性列表，null 或空列表表示无必需属性
     * @return 构建好的 Tool 对象
     */
    private static Tool defineTool(String name, String description,
                                   Map<String, Object> properties,
                                   List<String> required) {
        var schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(properties));

        // 只有非空时才添加 required 字段
        if (required != null && !required.isEmpty()) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(required));
        }

        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schemaBuilder.build())
                .build();
    }

    // ==================== 路径沙箱 ====================

    /**
     * 路径安全校验：确保文件操作不会逃逸出工作目录。
     * <p>
     * 防止模型通过 "../../etc/passwd" 这类路径穿越攻击读取或修改系统文件。
     * <p>
     * 对应 Python 原版：safe_path(p) 函数。
     *
     * @param relativePath 相对路径字符串
     * @return 安全的绝对路径
     * @throws SecurityException 如果路径逃逸出工作目录
     */
    private static Path safePath(String relativePath) {
        Path resolved = WORK_DIR.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORK_DIR)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    // ==================== 工具实现 ====================

    /**
     * 执行 shell 命令。
     * <p>
     * 安全特性：
     * - 危险命令黑名单检查（rm -rf /、sudo、shutdown 等）
     * - 120 秒超时自动终止
     * - 输出截断到 50000 字符
     * - OS 自适应：Windows 用 cmd /c，Unix 用 bash -c
     * <p>
     * 对应 Python 原版：run_bash(command) 函数。
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr 合并）
     */
    private static String runBash(String command) {
        // 危险命令拦截
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (command.contains(dangerous)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            // OS 自适应：选择正确的 shell
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(WORK_DIR.toFile());
            pb.redirectErrorStream(true); // 合并 stdout 和 stderr

            Process process = pb.start();

            // 读取输出，边读边检查长度
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                    // 提前截断，避免内存溢出
                    if (output.length() > MAX_OUTPUT) {
                        break;
                    }
                }
            }

            // 等待进程结束，带超时
            boolean finished = process.waitFor(BASH_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (" + BASH_TIMEOUT + "s)";
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "(no output)";
            }
            // 最终截断保护
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 读取文件内容。
     * <p>
     * 安全特性：
     * - 路径沙箱校验（防止路径穿越）
     * - 可选行数限制（limit 参数）
     * - 输出截断到 50000 字符
     * <p>
     * 对应 Python 原版：run_read(path, limit) 函数。
     *
     * @param path  相对文件路径
     * @param limit 最大读取行数，null 表示读取全部
     * @return 文件内容字符串
     */
    private static String runRead(String path, Integer limit) {
        try {
            Path safePath = safePath(path);
            List<String> lines = Files.readAllLines(safePath);

            // 应用行数限制
            if (limit != null && limit > 0 && limit < lines.size()) {
                int totalLines = lines.size();
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (totalLines - limit) + " more lines)");
            }

            String result = String.join("\n", lines);
            // 截断过长输出
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容。
     * <p>
     * 安全特性：
     * - 路径沙箱校验（防止路径穿越）
     * - 自动创建父目录
     * <p>
     * 对应 Python 原版：run_write(path, content) 函数。
     *
     * @param path    相对文件路径
     * @param content 要写入的内容
     * @return 操作结果描述
     */
    private static String runWrite(String path, String content) {
        try {
            Path safePath = safePath(path);
            // 自动创建父目录（像 Python 的 mkdir -p 一样）
            Files.createDirectories(safePath.getParent());
            Files.writeString(safePath, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确文本替换（仅替换第一次出现）。
     * <p>
     * 使用 Pattern.quote() 确保 old_text 作为字面量匹配。
     * <p>
     * 对应 Python 原版：run_edit(path, old_text, new_text) 函数。
     *
     * @param path    相对文件路径
     * @param oldText 要查找的文本
     * @param newText 替换后的文本
     * @return 操作结果描述
     */
    private static String runEdit(String path, String oldText, String newText) {
        try {
            Path safePath = safePath(path);
            String content = Files.readString(safePath);

            // 先做快速检查，避免不必要的正则编译
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            // 使用 Pattern.quote 确保字面量匹配
            String updated = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldText),
                    java.util.regex.Matcher.quoteReplacement(newText));
            Files.writeString(safePath, updated);
            return "Edited " + path;

        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 保存记忆的包装方法。
     * <p>
     * 对应 Python 原版：run_save_memory(name, description, mem_type, content) 函数。
     * 转发到全局 memoryMgr 实例。
     *
     * @param name        记忆标识名
     * @param description 一句话摘要
     * @param memType     记忆类型
     * @param content     完整内容
     * @return 操作结果描述
     */
    private static String runSaveMemory(String name, String description, String memType, String content) {
        return memoryMgr.saveMemory(name, description, memType, content);
    }

    // ==================== 系统提示词构建 ====================

    /**
     * 动态构建系统提示词，注入当前记忆内容。
     * <p>
     * 每次 LLM 调用前重建，确保同一会话中刚保存的记忆
     * 在下一轮 LLM 调用时即可见。
     * <p>
     * 对应 Python 原版：build_system_prompt() 函数。
     *
     * @return 完整的系统提示词字符串
     */
    private static String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();
        parts.add("You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.");

        // 注入记忆内容（如果有的话）
        String memorySection = memoryMgr.loadMemoryPrompt();
        if (!memorySection.isEmpty()) {
            parts.add(memorySection);
        }

        // 注入记忆使用指南
        parts.add(MEMORY_GUIDANCE);

        return String.join("\n\n", parts);
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 转换为普通 Java 对象。
     * <p>
     * 递归转换 Map/List/String/Number/Boolean 等 Java 原生类型，
     * 以便在工具分发表中统一处理。
     *
     * @param value JsonValue 实例
     * @return 对应的 Java 原生对象
     */
    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        // 字符串（最常见的类型，优先检查）
        var strOpt = value.asString();
        if (strOpt.isPresent()) return strOpt.get();

        // 数字
        var numOpt = value.asNumber();
        if (numOpt.isPresent()) return numOpt.get();

        // 布尔值
        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) return boolOpt.get();

        // 对象（Map）—— 递归转换每个值
        try {
            var mapOpt = value.asObject();
            if (mapOpt.isPresent()) {
                Map<String, JsonValue> raw = (Map<String, JsonValue>) (Object) mapOpt.get();
                Map<String, Object> result = new LinkedHashMap<>();
                for (var entry : raw.entrySet()) {
                    result.put(entry.getKey(), jsonValueToObject(entry.getValue()));
                }
                return result;
            }
        } catch (ClassCastException ignored) {
            // 类型不匹配，继续尝试下一种
        }

        // 数组（List）—— 递归转换每个元素
        try {
            var listOpt = value.asArray();
            if (listOpt.isPresent()) {
                List<JsonValue> raw = (List<JsonValue>) (Object) listOpt.get();
                List<Object> result = new ArrayList<>();
                for (JsonValue item : raw) {
                    result.add(jsonValueToObject(item));
                }
                return result;
            }
        } catch (ClassCastException ignored) {
            // 类型不匹配
        }

        return null;
    }

    // ==================== Agent 核心循环 ====================

    /**
     * Agent 核心循环：LLM 调用 → 工具执行 → 结果回传。
     * <p>
     * 与 S02 的区别：系统提示词在每次循环中动态重建，
     * 以包含最新保存的记忆内容。
     * <p>
     * 核心模式：
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       system = buildSystemPrompt();  // 动态重建（记忆感知）
     *       response = LLM(system, messages, tools);
     *       execute tools;
     *       append results;
     *   }
     * </pre>
     * <p>
     * 对应 Python 原版：agent_loop(messages) 函数。
     *
     * @param client        Anthropic API 客户端
     * @param model         模型 ID
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     * @param tools         工具定义列表（发送给 LLM）
     * @param toolHandlers  工具分发表：工具名 → 处理函数
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers) {
        while (true) {
            // ---- 0. 动态重建系统提示词（每次循环都重建，记忆感知） ----
            String systemPrompt = buildSystemPrompt();

            // ---- 1. 调用 LLM ----
            // 注意：这里需要在每次调用时使用新的 system prompt
            // 因为 SDK 的 paramsBuilder 中 system 是在 build() 时读取的
            // 我们通过重新构建参数来实现动态更新
            var loopParamsBuilder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(8000L)
                    .system(systemPrompt)
                    .messages(paramsBuilder.build().messages());
            for (Tool tool : tools) {
                loopParamsBuilder.addTool(tool);
            }

            Message response = client.messages().create(loopParamsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复给用户
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return; // 跳出循环，回到 REPL 等待下一个用户输入
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    // 从 JsonValue 提取输入参数（转换为 Map<String, Object>）
                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    // 从分发表查找并执行对应的工具处理函数
                    Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                    String output;
                    if (handler != null) {
                        output = handler.apply(input);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    // 打印工具调用日志：> toolName: 输出预览
                    System.out.println(bold("> " + toolName) + ":");
                    System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));

                    // 构造 tool_result 消息块，回传给 LLM
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            // API 要求 tool_result 必须以 user 角色发送
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：读取用户输入 → 追加到对话历史 → 执行 Agent 循环 → 打印结果。
     * <p>
     * 整体流程：
     * <pre>
     * while True:
     *     query = input("s09 >> ")     # 读取用户输入
     *     if query == "/memories":     # 列出当前记忆
     *         list_memories()
     *         continue
     *     messages.append(query)       # 追加到历史
     *     agent_loop(messages)         # 执行 Agent 循环（记忆感知）
     * </pre>
     * <p>
     * 与 S02 的核心区别：
     * 1. 系统提示词在每次 LLM 调用前动态重建（注入记忆内容）
     * 2. 多了 save_memory 工具（允许模型主动保存记忆）
     * 3. 多了 /memories REPL 命令（列出当前记忆）
     * 4. 启动时自动加载已有记忆
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 启动时加载已有记忆 ----
        // 对应 Python 原版：memory_mgr.load_all()
        memoryMgr.loadAll();
        int memCount = memoryMgr.size();
        if (memCount > 0) {
            System.out.println("[" + memCount + " memories loaded into context]");
        } else {
            System.out.println("[No existing memories. The agent can create them with save_memory.]");
        }

        // ---- 定义 5 个工具 ----
        // S09 在 S02 的 4 个工具基础上增加 save_memory
        List<Tool> tools = List.of(
                // bash：执行 shell 命令
                defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),

                // read_file：读取文件内容（limit 可选，限制读取行数）
                defineTool("read_file", "Read file contents.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),

                // write_file：写入文件内容（自动创建父目录）
                defineTool("write_file", "Write content to file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),

                // edit_file：精确文本替换（只替换第一次出现的位置）
                defineTool("edit_file", "Replace exact text in file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),

                // save_memory：保存持久记忆（跨会话存活）
                // 对应 Python 原版中 TOOLS 列表的最后一个工具
                defineTool("save_memory", "Save a persistent memory that survives across sessions.",
                        Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "Short identifier (e.g. prefer_tabs, db_schema)"),
                                "description", Map.of(
                                        "type", "string",
                                        "description", "One-line summary of what this memory captures"),
                                "type", Map.of(
                                        "type", "string",
                                        "enum", List.of("user", "feedback", "project", "reference"),
                                        "description", "user=preferences, feedback=corrections, project=non-obvious project conventions or decision reasons, reference=external resource pointers"),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "Full memory content (multi-line OK)")),
                        List.of("name", "description", "type", "content"))
        );

        // ---- 工具分发表：工具名 → 处理函数 ----
        Map<String, Function<Map<String, Object>, String>> toolHandlers = new LinkedHashMap<>();
        toolHandlers.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command);
        });
        toolHandlers.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            Integer limit = null;
            Object limitObj = input.get("limit");
            if (limitObj instanceof Number num) limit = num.intValue();
            return runRead(path, limit);
        });
        toolHandlers.put("write_file", input -> {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (content == null) return "Error: content is required";
            return runWrite(path, content);
        });
        toolHandlers.put("edit_file", input -> {
            String path = (String) input.get("path");
            String oldText = (String) input.get("old_text");
            String newText = (String) input.get("new_text");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (oldText == null) return "Error: old_text is required";
            if (newText == null) return "Error: new_text is required";
            return runEdit(path, oldText, newText);
        });
        toolHandlers.put("save_memory", input -> {
            String name = (String) input.get("name");
            String description = (String) input.get("description");
            String memType = (String) input.get("type");
            String content = (String) input.get("content");
            if (name == null || name.isBlank()) return "Error: name is required";
            if (description == null) return "Error: description is required";
            if (memType == null) return "Error: type is required";
            if (content == null) return "Error: content is required";
            return runSaveMemory(name, description, memType, content);
        });

        // ---- 构建消息参数（包含模型、工具、maxTokens） ----
        // 注意：系统提示词在 agentLoop 中动态设置，这里不设置
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S09 Memory System") + " — 5 tools: bash, read_file, write_file, edit_file, save_memory");
        System.out.println("Type 'q' or 'exit' to quit. Type '/memories' to list current memories.\n");

        while (true) {
            // 打印提示符（青色 "s09 >>"）
            System.out.print(cyan("s09 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            // 空输入或退出命令 → 结束
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // /memories 命令：列出当前所有记忆
            if ("/memories".equals(query)) {
                Map<String, Map<String, String>> memories = memoryMgr.getMemories();
                if (memories.isEmpty()) {
                    System.out.println("  (no memories)");
                } else {
                    for (var entry : memories.entrySet()) {
                        String name = entry.getKey();
                        Map<String, String> mem = entry.getValue();
                        System.out.println("  [" + mem.get("type") + "] " + name + ": " + mem.get("description"));
                    }
                }
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环（LLM 调用 + 工具执行，记忆感知）
            try {
                agentLoop(client, model, paramsBuilder, tools, toolHandlers);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
