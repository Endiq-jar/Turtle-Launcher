package kr.co.donghyun.turtlelauncher.domain.repository

import kr.co.donghyun.turtlelauncher.data.key.KeyButton

/**
 * 가상 키패드 레이아웃 저장소 계약.
 * ⚠️ KeyButton 은 이미 순수 데이터 클래스(좌표/크기 등 기하 정보)라 domain 전용 타입으로
 * 복제하지 않고 그대로 재사용한다 — Renderer 와 동일한 판단.
 */
interface KeyLayoutRepository {
    fun getLayout(): List<KeyButton>
    fun saveLayout(layout: List<KeyButton>)
    fun resetLayout(): List<KeyButton>
}
