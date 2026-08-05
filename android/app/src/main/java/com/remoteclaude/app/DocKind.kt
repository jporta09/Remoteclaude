package com.remoteclaude.app

/** Cómo se muestra un documento compartido, según su extensión. */
enum class DocKind {
    IMAGE, PDF, TEXT, OTHER;

    companion object {
        fun of(name: String): DocKind = when (name.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> IMAGE
            "pdf" -> PDF
            "txt", "csv", "tsv", "md", "markdown", "log", "json", "yaml", "yml",
            "xml", "html", "htm", "ini", "conf", "sh", "py", "kt", "js", "ts",
            "c", "cpp", "h", "go", "rs", "sql", "toml", "" -> TEXT
            else -> OTHER
        }
    }
}
