# Chat

## 添加 learn-claude-code 项目

git submodule add https://github.com/shareAI-lab/learn-claude-code vendors/learn-claude-code


## learn-claude-code 项目分析

仔细阅读 @vendors/learn-claude-code 的代码，撰写一个详细的架构分析文档，如需图表，使用 mermaid chart。文档放在: ./specs/learn-claude-code-arch.md


## mini-agent-4j 项目分析

仔细阅读 @vendors/learn-claude-code 项目内容，然后请你分析该项目能不能用java语言及java相关技术栈（框架）进行重写，分析完后撰写一份重写分析文档，给出可行性分析，如果可以重写，给出重写建议与方案。如需图表，使用 mermaid chart，文档放在: @specs/目录下


根据 @specs/java-rewrite-analysis.md 文档，撰写一份详细的实施计划，如需图表，使用 mermaid chart，文档放在: @specs/目录下


你可以访问阅读@specs/ 目录下的所有文件，代码放在 @mini-agent-4j 目录下，项目管理使用 maven，你要根据 @specs/java-rewrite-impl-plan.md 文档，进行编码落地, 要求代码质量高，要求注释清晰，要求注释用中文。你每次执行完计划中的一个phase后，都要总结该phase的实现情况，并记录到 @specs/java-rewrite-impl-summary.md  文档中。

