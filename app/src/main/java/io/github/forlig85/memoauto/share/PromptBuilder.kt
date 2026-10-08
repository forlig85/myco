package io.github.forlig85.memoauto.share

import io.github.forlig85.memoauto.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 회의록 프롬프트 템플릿에 설정값을 채운다. */
object PromptBuilder {
    const val DEFAULT_TEMPLATE =
        "이 녹음파일을 전사해서 회의록으로 정리해줘. 형식: 핵심 요약, 주요 논의, 결정사항, Action Item(담당, 기한), " +
            "주요 리스크, 다음 회의 전 확인사항. {프로젝트} 과제 관련 회의일 경우 최종 결과를 노션의 {Notion 경로} 구조로 등록해줘.\n\n" +
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
