package com.helix.app.marketplace

import com.helix.app.R

object MarketplaceCatalog {
    fun items(): List<MarketplaceItem> = ITEMS

    private val ITEMS =
        listOf(
            MarketplaceItem(
                id = "cloudflare-docs",
                nameRes = R.string.marketplace_item_cloudflare_docs_name,
                type = MarketplaceItemType.MCP,
                summaryRes = R.string.marketplace_item_cloudflare_docs_summary,
                descriptionRes = R.string.marketplace_item_cloudflare_docs_desc,
                author = "Cloudflare",
                authRequirement = MarketplaceAuthRequirement.NONE,
                tags = listOf("docs", "cloudflare", "mcp", "search"),
                targetConnectorName = "cloudflare-docs-connector",
                payload =
                    """
                    {
                      "name": "cloudflare-docs-connector",
                      "mcpServers": {
                        "cloudflare-docs": {
                          "url": "https://docs.mcp.cloudflare.com/mcp"
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            MarketplaceItem(
                id = "github-operations",
                nameRes = R.string.marketplace_item_github_name,
                type = MarketplaceItemType.CONNECTOR,
                summaryRes = R.string.marketplace_item_github_summary,
                descriptionRes = R.string.marketplace_item_github_desc,
                author = "GitHub / MCP",
                authRequirement = MarketplaceAuthRequirement.BEARER_TOKEN,
                authHintRes = R.string.marketplace_item_github_auth_hint,
                tags = listOf("github", "code", "issues", "git"),
                targetConnectorName = "github-connector",
                payload =
                    """
                    {
                      "name": "github-connector",
                      "mcpServers": {
                        "github": {
                          "url": "https://mcp.github.com/mcp",
                          "headers": {
                            "Authorization": "Bearer placeholder"
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            MarketplaceItem(
                id = "linear-workspace",
                nameRes = R.string.marketplace_item_linear_name,
                type = MarketplaceItemType.CONNECTOR,
                summaryRes = R.string.marketplace_item_linear_summary,
                descriptionRes = R.string.marketplace_item_linear_desc,
                author = "Linear / MCP",
                authRequirement = MarketplaceAuthRequirement.API_KEY,
                authHintRes = R.string.marketplace_item_linear_auth_hint,
                tags = listOf("linear", "issues", "tasks", "management"),
                targetConnectorName = "linear-connector",
                payload =
                    """
                    {
                      "name": "linear-connector",
                      "mcpServers": {
                        "linear": {
                          "url": "https://mcp.linear.app/mcp",
                          "headers": {
                            "Authorization": "Bearer placeholder"
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            MarketplaceItem(
                id = "web-research",
                nameRes = R.string.marketplace_item_web_research_name,
                type = MarketplaceItemType.MCP,
                summaryRes = R.string.marketplace_item_web_research_summary,
                descriptionRes = R.string.marketplace_item_web_research_desc,
                author = "Helix Community",
                authRequirement = MarketplaceAuthRequirement.NONE,
                tags = listOf("web", "fetch", "search", "http"),
                targetConnectorName = "web-fetch-connector",
                payload =
                    """
                    {
                      "name": "web-fetch-connector",
                      "mcpServers": {
                        "web-fetch": {
                          "url": "https://mcp.fetch.helix.local/mcp"
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            MarketplaceItem(
                id = "code-review",
                nameRes = R.string.marketplace_item_code_review_name,
                type = MarketplaceItemType.SKILL,
                summaryRes = R.string.marketplace_item_code_review_summary,
                descriptionRes = R.string.marketplace_item_code_review_desc,
                author = "Helix Official",
                authRequirement = MarketplaceAuthRequirement.LOCAL,
                tags = listOf("code", "review", "security", "quality"),
                targetSkillName = "code-review",
                payload =
                    """
                    ---
                    name: code-review
                    description: Structured code quality, security, and architecture review.
                    license: Apache-2.0
                    metadata:
                      helix.built-in-version: "1"
                    ---
                    # Code review assistant

                    Review code changes systematically:
                    1. Verify security boundaries, input sanitization, and credential handling.
                    2. Check resource lifecycle, coroutine cancellation, and error handling.
                    3. Inspect backward compatibility, schema migrations, and test coverage.
                    4. Identify regressions or style inconsistencies before approving.
                    """.trimIndent(),
            ),
            MarketplaceItem(
                id = "sql-assistant",
                nameRes = R.string.marketplace_item_sql_assistant_name,
                type = MarketplaceItemType.SKILL,
                summaryRes = R.string.marketplace_item_sql_assistant_summary,
                descriptionRes = R.string.marketplace_item_sql_assistant_desc,
                author = "Helix Official",
                authRequirement = MarketplaceAuthRequirement.LOCAL,
                tags = listOf("sql", "database", "query", "schema"),
                targetSkillName = "sql-assistant",
                payload =
                    """
                    ---
                    name: sql-assistant
                    description: Safe read-only SQL queries and schema investigation.
                    license: Apache-2.0
                    metadata:
                      helix.built-in-version: "1"
                    ---
                    # SQL & database assistant

                    Guide database analysis safely:
                    1. Inspect schemas and indexes read-only before proposing queries.
                    2. Prefer deterministic, bounded SELECT queries with explicit limits.
                    3. Never execute destructive DDL/DML without user review and explicit approval.
                    4. Explain query execution plans and index usage clearly.
                    """.trimIndent(),
            ),
        )
}
