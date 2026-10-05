package com.schedulewidget.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/** Inputs are real SenseVoice outputs from the PC benchmark and a phone transcript. */
class KoreanSpacingTest {

    private fun check(raw: String, expected: String) = assertEquals(expected, KoreanSpacing.fix(raw))

    @Test
    fun joinsParticlesAndEndings() {
        check(
            "오늘 강의위 에 서는 데이터베이스 시스템 의 기본 개 념과 트렌젝션 처리 방식 에 대해 알아보 겠 습니다.",
            "오늘 강의위에서는 데이터베이스 시스템의 기본 개념과 트렌젝션 처리 방식에 대해 알아보겠습니다.",
        )
        check(
            "정규화 를 하 는 이유 는 데이터 의 중복 을 줄 이고 삽입.",
            "정규화를 하는 이유는 데이터의 중복을 줄이고 삽입.",
        )
        check(
            "오늘 저 녁에는 친구들 과 함께 학교 근처 식당 에서 밥 을 먹 기로 했어요.",
            "오늘 저녁에는 친구들과 함께 학교 근처 식당에서 밥을 먹기로 했어요.",
        )
    }

    @Test
    fun copulaAfterNoun() {
        check(
            "지난 시간 에 배운 정렬 알고리즘 중 에서  정렬 의 평균 시간 복잡 또는 앤 로그 앤 이 었 습니다",
            "지난 시간에 배운 정렬 알고리즘 중에서 정렬의 평균 시간 복잡 또는 앤 로그 앤이었습니다",
        )
    }

    @Test
    fun hadaVerbsJoinTheNounButNotAnEnding() {
        check(
            "지 속성 이라는 네 가지 성질 을 반드시 만족 해야 합니다.",
            "지속성이라는 네 가지 성질을 반드시 만족해야 합니다.",
        )
        check(
            "관 계형 데이터베스 에 서는 테이블 사 이의 관계 를 외래 키를 이용 해서 표현 합니다.",
            "관계형 데이터베스에서는 테이블 사이의 관계를 외래 키를 이용해서 표현합니다.",
        )
        check(
            "교착 상 태가 발생 하면 운영 체 제나 데이터베이스 는 희생 자를 골라 트렌잭션 을 철회 해야 합니다",
            "교착 상태가 발생하면 운영 체제나 데이터베이스는 희생자를 골라 트렌잭션을 철회해야 합니다",
        )
    }

    @Test
    fun numbersAndUnits() {
        check(
            "중간 고사 는 10 월 23 일 목요일 오후 2 시에 공학관 3001 호 에서 진행 됩니다.",
            "중간 고사는 10월 23일 목요일 오후 2시에 공학관 3001호에서 진행됩니다.",
        )
        // 50만 원: the counter 원 stays a word of its own
        check(
            "이 예제 에서 계좌 잔액 은 50 만 원이 었 고, 20 만 원 을 이 체한 뒤 30 만 원이 남 게 됩니다",
            "이 예제에서 계좌 잔액은 50만 원이었고, 20만 원을 이 체한 뒤 30만 원이 남게 됩니다",
        )
    }

    @Test
    fun keepsDemonstrativesDeterminersAndNouns() {
        check(
            "이 네 가지 성질 을 영어 첫 글 자를 따 서 흔히 애쉬 드라고 부릅니다.",
            "이 네 가지 성질을 영어 첫 글자를 따서 흔히 애쉬 드라고 부릅니다.",
        )
        check(
            "경제 성 장률 이 둔화 되 면서 정부 는 새로운 경기 부양책 을 발 표했 습니다.",
            "경제 성장률이 둔화되면서 정부는 새로운 경기 부양책을 발표했습니다.",
        )
        check(
            "스마트폰 을 너무 오래 사용 하면 눈 이 쉽 게 피로 해질 수 있 으니 주의 하 세요.",
            "스마트폰을 너무 오래 사용하면 눈이 쉽게 피로 해질 수 있으니 주의하세요.",
        )
    }

    @Test
    fun naturallySpacedTextIsUntouched() {
        val samples = listOf(
            // Whisper / Qwen3 output and reference sentences
            "오늘 강의에서는 데이터베이스 시스템의 기본 개념과 트랜잭션 처리 방식에 대해 알아보겠습니다",
            "지난 시간에 배운 정렬 알고리즘 중에서 퀵 정렬의 평균 시간 복잡 또는 n log n 이었습니다",
            "이 네 가지 성질을 영어 첫 글자를 따서 흔히 ACID라고 부릅니다.",
            "이 예제에서 계좌 잔액은 50만 원이었고, 20만 원을 이체한 뒤 30만 원이 남게 됩니다.",
            "교착 상태가 발생하면 운영체제나 데이터베이스는 희생자를 골라 트랜잭션을 철회해야 합니다.",
            "예를 들어 학생 테이블에 만 명이 있고 그중 컴퓨터공학과 학생이 2500명이라면 선택도는 0.25입니다.",
            "그럼 오늘 수업은 여기까지 하고, 다음 시간에는 분산 트랜잭션과 이단계 커밋 프로토콜을 다루겠습니다.",
            "pet.json을 고를 때는 스프라이트 이미지도 함께 선택하세요.",
            "일관성.",
            "",
        )
        for (s in samples) assertEquals(s, KoreanSpacing.fix(s))
    }

    @Test
    fun onlyRemovesSpacesAndIsIdempotent() {
        val raw = listOf(
            "시험 범위 는 교재 한 장 부터 6 장 까지 이며 전체 성 적의 30 를 차지 합니다",
            "김민수 학 생이 질한 것 처럼. 인덱스 를 너무 많이 만들 면 오히려 쓰기 성 능이 떨어질 수 있 습니다",
            "히 트리 인덱스 는 디스크 접근 횟수 를 최소화 하 도록 설계 된 균형 트이 구조 입니다",
            "운동 을 꾸준히 하면 건강 이 좋 아질 뿐 만 아 니라 스 트레 스도 줄어 듭니다.",
            "실습 서버 의 메모리 는 64 기가 바이트 이고, 코어 는 모두 16 개가 있 습니다.",
        )
        for (s in raw) {
            val fixed = KoreanSpacing.fix(s)
            assertEquals(s.replace(" ", ""), fixed.replace(" ", ""))
            assertEquals(fixed, KoreanSpacing.fix(fixed))
        }
    }
}
