# 参与贡献

感谢你愿意改进 Career Orbit。

## 开始之前

1. 先搜索现有 Issue，避免重复工作。
2. 较大的功能改动请先创建 Issue，说明使用场景和实现方向。
3. 不要在 Issue、日志、测试数据或提交中包含 API Key、真实简历和个人信息。

## 本地开发

```bash
git clone <repository-url>
cd career-orbit
cp .env.example .env
docker compose up -d
```

后端和前端分别位于 `backend/` 与 `frontend/`。详细步骤见 [README.md](README.md)。

## 提交前检查

```bash
cd backend && ./mvnw test
cd frontend && npm ci && npm test && npm run build
```

## Pull Request

- 一个 PR 只处理一个主题。
- 清楚描述问题、修改内容和验证方式。
- UI 变更请附当前代码的运行截图。
- 新功能应补充测试，或说明暂时无法自动化验证的原因。
- 破坏性变更必须在描述中明确标注。

建议使用 `feat:`、`fix:`、`docs:`、`test:`、`refactor:`、`chore:` 等提交前缀。

