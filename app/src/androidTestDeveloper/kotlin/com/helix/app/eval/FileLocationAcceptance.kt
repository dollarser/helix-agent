package com.helix.app.eval

internal enum class FileLocationAcceptance(
    val argument: String,
    val prompt: String,
) {
    AUTONOMOUS("fileLocationModelAcceptance", ""),
    SEARCH_TOOLS(
        "fileLocationSearchToolsAcceptance",
        "本次另外验证搜索工具：请分别用 glob 的路径匹配和 grep 的正文搜索命中这份当前有效记录，" +
            "再用 read 读取核对。两种搜索结果都需用于定位，不用逐个读取全部历史文件。",
    ),
}
