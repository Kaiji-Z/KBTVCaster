# v0.1.2 更新日志

**发布日期**: 2026-01-19
**版本号**: 0.1.2
**状态**: ✅ 测试通过，稳定版本

## 📋 版本概述

新增项目版本记录skill功能，用于系统化管理项目版本的发布和回退。

## ✨ 新功能

### Project Versioning Skill
- 创建 `.opencode/skills/project-versioning/` 目录
- `project-versioning.json` - 机器可读的skill定义
- `project-versioning.md` - 人类可读的skill指南
- 支持语义化版本管理
- 包含完整的changelog模板
- 提供回退命令

### Skill触发短语
- "记录项目"
- "record project"
- "版本"
- "version"
- "create release"
- "发布版本"

## 🔧 项目清理

- 整理项目文件结构
- 将文档和旧脚本移至 `temp/` 目录
- 删除无用文件 (`nul`)
- 优化 `.opencode/skills/` 结构

## 📁 变更文件

```
.opencode/skills/project-versioning/
├── project-versioning.json
└── project-versioning.md

SKILL_project_versioning.md
```

## 🔀 回退到本版本

```bash
git checkout v0.1.2
# 或
git reset --hard v0.1.2
```

## 📝 提交记录

- `62ddb66` - Restructure skill files into subfolder
- `3aeb9b8` - Add project versioning skill definition
- `84c6da7` - Add v0.1.1 changelog

## 📊 测试结果

- ✅ Skill文件创建成功
- ✅ Git tag创建成功
- ✅ 项目结构清晰
- ✅ 可随时回退到任何版本

## 📌 版本信息

- **当前版本**: v0.1.2
- **versionName**: "0.1.2"
- **前一个版本**: v0.1.1

---

**上一个版本**: [v0.1.1](./VERSION_v0.1.1.md)
