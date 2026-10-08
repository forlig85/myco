package io.github.forlig85.memoauto.share

import io.github.forlig85.memoauto.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 회의록 프롬프트 템플릿에 설정값을 채운다. */
object PromptBuilder {
    const val DEFAULT_TEMPLATE =
        "이 녹음파일을 전사해서 회의록으로 정리해줘.\n" +
            "형식: 핵심 요약, 주요 논의, 결정사항, Action Item(담당, 기한), 주요 리스크, 다음 회의 전 확인사항.\n\n" +
            "정리가 끝나면 녹음 내용을 보고 노션의 {Notion 경로} 아래에서 이 회의에 맞는 분류(과제·프로젝트 등)를 찾아줘. " +
            "맞는 분류가 이미 있으면 그 분류에 회의록을 등록하고, 없으면 적절한 이름으로 새 분류를 만든 뒤 등록해줘. " +
            "마지막에 어느 분류에 등록했는지(기존/신규)와 페이지 제목을 알려줘.\n\n" +
            "회의 유형: {회의 유형}\n회의 일시: {날짜}\n녹음 파일: {파일명}"

    val PLACEHOLDERS = listOf("{프로젝트}", "{Notion 경로}", "{회의 유형}", "{날짜}", "{파일명}")

    fun build(fileName: String?, template: String = Prefs.promptTemplate): String {
        val date = fileName?.let { parseDate(it) } ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
        return template
            .replace("{프로젝트}", Prefs.projectName)
            .replace("{Notion 경로}", Prefs.notionPath)
            .replace("{회의 유형}", Prefs.meetingType)
            .replace("{날짜}", date)
            .replace("{파일명}", fileName ?: "")
    }

    /** "2026-10-08_1000_회의녹음.m4a" → "2026-10-08 10:00" */
    private fun parseDate(name: String): String? {
        val m = Regex("""^(\d{4}-\d{2}-\d{2})_(\d{2})(\d{2})""").find(name) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}"
    }
}
