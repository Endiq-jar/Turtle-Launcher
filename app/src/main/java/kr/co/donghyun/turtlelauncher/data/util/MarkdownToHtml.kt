package kr.co.donghyun.turtlelauncher.data.util

/**
 * 아주 가벼운 마크다운 → HTML 변환기.
 *
 * Modrinth 프로젝트 body(및 GitHub 릴리스 노트)는 GitHub-flavored 마크다운인데,
 * 여기에 원시 HTML(`<img>`, `<details>` 등)이 섞여 들어오기도 한다. 예전에는 정규식으로
 * 마크다운 기호만 벗겨 평문으로 보여줬는데, 그러면 섞여 있던 HTML 태그가 그대로 노출됐다.
 *
 * 완전한 CommonMark 파서는 아니고(중첩 리스트/각주 등 미지원), 실제 설명글에서 흔한
 *  - 코드펜스(```), 인라인 코드
 *  - 제목(#..######), 수평선(--- *** ___)
 *  - 이미지, 링크, 굵게/기울임
 *  - 순서/비순서 목록, 인용(>)
 *  - 문단/줄바꿈
 * 를 커버한다. 원시 HTML 은 건드리지 않고 그대로 통과시켜 WebView 가 렌더하도록 둔다.
 */
fun markdownToHtml(markdown: String): String {
    if (markdown.isBlank()) return ""

    val src = markdown.replace("\r\n", "\n").replace("\r", "\n")
    val out = StringBuilder()
    val lines = src.split("\n")

    var i = 0
    var inUl = false
    var inOl = false

    fun closeLists() {
        if (inUl) { out.append("</ul>"); inUl = false }
        if (inOl) { out.append("</ol>"); inOl = false }
    }

    while (i < lines.size) {
        val line = lines[i].trimEnd()

        // 코드펜스 ``` ... ```
        if (Regex("^\\s*```+\\s*[\\w+-]*\\s*$").matches(line)) {
            closeLists()
            out.append("<pre><code>")
            i++
            while (i < lines.size && !Regex("^\\s*```+\\s*$").matches(lines[i])) {
                out.append(escapeHtml(lines[i])).append("\n")
                i++
            }
            out.append("</code></pre>")
            i++ // 닫는 펜스 소비
            continue
        }

        // 빈 줄 → 리스트/문단 경계
        if (line.isBlank()) {
            closeLists()
            i++
            continue
        }

        // 수평선
        if (Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(line)) {
            closeLists()
            out.append("<hr>")
            i++
            continue
        }

        // 제목
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (heading != null) {
            closeLists()
            val level = heading.groupValues[1].length
            out.append("<h").append(level).append(">")
                .append(inline(heading.groupValues[2]))
                .append("</h").append(level).append(">")
            i++
            continue
        }

        // 인용문(연속 줄 묶기)
        if (Regex("^\\s*>").containsMatchIn(line)) {
            closeLists()
            val buf = StringBuilder()
            while (i < lines.size && Regex("^\\s*>").containsMatchIn(lines[i])) {
                buf.append(lines[i].replaceFirst(Regex("^\\s*>\\s?"), "")).append("\n")
                i++
            }
            out.append("<blockquote>").append(inline(buf.toString().trim())).append("</blockquote>")
            continue
        }

        // 비순서 목록
        val ulItem = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
        if (ulItem != null) {
            if (inOl) { out.append("</ol>"); inOl = false }
            if (!inUl) { out.append("<ul>"); inUl = true }
            out.append("<li>").append(inline(ulItem.groupValues[1])).append("</li>")
            i++
            continue
        }

        // 순서 목록
        val olItem = Regex("^\\s*\\d+[.)]\\s+(.*)$").find(line)
        if (olItem != null) {
            if (inUl) { out.append("</ul>"); inUl = false }
            if (!inOl) { out.append("<ol>"); inOl = true }
            out.append("<li>").append(inline(olItem.groupValues[1])).append("</li>")
            i++
            continue
        }

        // 원시 HTML 블록 줄(<로 시작) — 그대로 통과
        if (line.trimStart().startsWith("<")) {
            closeLists()
            out.append(line).append("\n")
            i++
            continue
        }

        // 일반 문단 — 뒤따르는 일반 줄들을 <br>로 이어붙임
        closeLists()
        val para = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i].trimEnd()
            if (next.isBlank()) break
            if (isBlockStart(next)) break
            para.append("\n").append(next)
            i++
        }
        out.append("<p>").append(inline(para.toString()).replace("\n", "<br>")).append("</p>")
    }
    closeLists()
    return out.toString()
}

/** 다음 줄이 새 블록(제목/리스트/인용/펜스/hr/HTML)을 여는지 판정 — 문단 병합 중단용. */
private fun isBlockStart(line: String): Boolean {
    return Regex("^#{1,6}\\s+").containsMatchIn(line) ||
        Regex("^\\s*[-*+]\\s+").containsMatchIn(line) ||
        Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) ||
        Regex("^\\s*>").containsMatchIn(line) ||
        Regex("^\\s*```+").containsMatchIn(line) ||
        Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(line) ||
        line.trimStart().startsWith("<")
}

// 인라인 코드 보호용 센티넬 — 본문에 나타날 일 없는 제어문자.
private const val CODE_OPEN = "\u0001"
private const val CODE_CLOSE = "\u0002"

/** 인라인 요소(이미지/링크/굵게/기울임/코드) 변환. 인라인 코드 안은 보호. */
private fun inline(text: String): String {
    // 인라인 코드를 먼저 추출해 센티넬로 감싼 인덱스로 치환(그 안은 추가 변환 금지).
    val codes = mutableListOf<String>()
    var s = Regex("`([^`]+)`").replace(text) { m ->
        codes.add(m.groupValues[1])
        CODE_OPEN + (codes.size - 1).toString() + CODE_CLOSE
    }

    // 이미지 (링크보다 먼저)
    s = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)").replace(s) { m ->
        "<img src=\"" + m.groupValues[2] + "\" alt=\"" + m.groupValues[1] + "\">"
    }
    // 링크
    s = Regex("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)").replace(s) { m ->
        "<a href=\"" + m.groupValues[2] + "\">" + m.groupValues[1] + "</a>"
    }
    // 굵게
    s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { "<strong>" + it.groupValues[1] + "</strong>" }
    s = Regex("__([^_]+)__").replace(s) { "<strong>" + it.groupValues[1] + "</strong>" }
    // 기울임
    s = Regex("(?<![*])\\*([^*\\n]+)\\*(?![*])").replace(s) { "<em>" + it.groupValues[1] + "</em>" }
    s = Regex("(?<![_])_([^_\\n]+)_(?![_])").replace(s) { "<em>" + it.groupValues[1] + "</em>" }

    // 보호했던 인라인 코드 복원
    s = Regex(CODE_OPEN + "(\\d+)" + CODE_CLOSE).replace(s) { m ->
        val idx = m.groupValues[1].toInt()
        "<code>" + escapeHtml(codes[idx]) + "</code>"
    }
    return s
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
