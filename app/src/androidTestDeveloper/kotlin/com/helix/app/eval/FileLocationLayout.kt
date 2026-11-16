package com.helix.app.eval

/** Layouts vary source identity and task wording, without prescribing a search strategy. */
internal enum class FileLocationLayout(
    val targetDirectory: String,
    val oldDirectory: String,
    val otherPath: String,
    val request: String,
) {
    BASELINE(
        "teams/flight/releases",
        "archive",
        "teams/other/release-current.md",
        "定位 ORCHID 项目当前有效的发布记录。目录有历史版本和其他项目，请自行查找并核实正文。",
    ),
    CONTENT(
        "workspace/staging/notes",
        "workspace/mixed",
        "workspace/staging/other/release-current.md",
        "查找正文同时属于 ORCHID 项目且状态为 CURRENT 的发布记录。文件名不可靠，其他记录可能过期或属于别的项目。",
    ),
    UNICODE_HOLDOUT(
        "资料/待确认/交接",
        "资料/历史",
        "资料/待确认/其他/release-current.md",
        "找出 ORCHID 的现行发布记录，给我依据；不要把同目录的其他项目或已被替代的记录当成答案。",
    ),
}
