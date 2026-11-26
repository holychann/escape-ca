package kr.escape.car.guide

/**
 * Every word of the in-app rulebook that is not the rulebook itself: button names and short labels. The rulebook's
 * sentences come from docs/차근차근탐험단_룰북.md only (copied to the app's assets as [ASSET] at build time), so when
 * the rulebook changes, the document is the one place to edit; when a label changes, this file is.
 */
object GuideText {
    /** Where the build puts the rulebook among the app's assets (see car/app/build.gradle.kts, copyRulebook). */
    const val ASSET = "rulebook/rulebook.md"

    // 「놀이 전 약속」
    const val PROMISE_BADGE = "보호자 확인"
    const val PROMISE_GO = "약속했어요! 출발"
    const val PROMISE_BACK = "돌아가기"
    const val PROMISE_SKIP_TODAY = "오늘은 다시 안 보기"
    const val PROMISE_SKIP_NOTE = "오늘 하루는 시작 버튼을 누르면 바로 시작해요"
    const val PROMISE_ALL_CHECKED = "다섯 가지를 모두 확인해야 출발할 수 있어요"
    const val KID_NOT_USED = "이건 안 만져요"
    const val KID_USED = "이 버튼들로 놀아요"

    // [사용법]
    const val GUIDE_BUTTON = "사용법"
    const val GUIDE_CLOSE = "닫기"
    const val GUIDE_PAUSED = "사용법을 보는 동안 놀이는 멈춰 있어요. 닫은 뒤 [이어 하기]를 눌러 주세요"
    const val GUIDE_KID_TITLE = "탐험대장이 읽는 장"
    const val READ = "읽어 줘"
    const val READ_STOP = "그만 읽기"
    const val OPEN = "펼치기"
    const val CLOSE = "접기"

    /** Shown over the rulebook while the car is not parked, the same rule as the rest of the app. */
    const val PARKED_ONLY = "주차(P)하면 다시 볼 수 있어요"

    // remembered on the device
    const val PREFS = "screen"
    const val PREF_SKIP_DATE = "promiseSkipDate"
}
