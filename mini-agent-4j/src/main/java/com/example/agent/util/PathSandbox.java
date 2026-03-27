package com.example.agent.util;

import java.nio.file.Path;

/**
 * 路径沙箱：防止路径穿越攻击。
 * <p>
 * 所有文件操作（读、写、编辑）都必须经过此类校验，
 * 确保操作路径不会逃逸出指定的工作目录。
 * <p>
 * 对应 Python 原版：safe_path(p) 函数。
 * Java 版使用 Path.normalize() + startsWith() 实现更严格的检查。
 */
public final class PathSandbox {

    /** 工作目录的绝对路径（已规范化） */
    private final Path workDir;

    /**
     * 创建路径沙箱。
     *
     * @param workDir 允许操作的工作目录
     */
    public PathSandbox(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    /**
     * 校验并解析相对路径，确保结果在工作目录内。
     * <p>
     * 处理流程：
     * 1. 将输入路径拼接到工作目录
     * 2. 规范化（消除 .. 和 .）
     * 3. 检查是否仍在工作目录下
     *
     * @param relativePath 相对路径字符串
     * @return 安全的绝对路径
     * @throws SecurityException 如果路径逃逸出工作目录
     */
    public Path safePath(String relativePath) {
        // 拼接并规范化
        Path resolved = workDir.resolve(relativePath).normalize().toAbsolutePath();

        // 检查是否仍在工作目录下
        if (!resolved.startsWith(workDir)) {
            throw new SecurityException(
                    "路径逃逸工作区: " + relativePath + " -> " + resolved);
        }

        return resolved;
    }

    /**
     * 获取工作目录。
     *
     * @return 工作目录路径
     */
    public Path getWorkDir() {
        return workDir;
    }
}
