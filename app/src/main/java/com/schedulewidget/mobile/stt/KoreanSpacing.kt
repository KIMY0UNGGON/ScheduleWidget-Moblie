package com.schedulewidget.mobile.stt

/**
 * Word spacing for SenseVoice's Korean output. The model emits its SentencePiece word marker in front of most
 * morphemes (the tokens themselves carry the misplaced spaces, so they cannot be rebuilt from `tokens`):
 * "오늘 강의 에 서는 데이터베이스 시스템 의 기본 개 념과 ... 알아보 겠 습니다." Rule-based joiner, no
 * dictionary model: a space is removed when the right fragment is a particle/ending that cannot start a word
 * (with 은/는, 이/가, 을/를 ... checked against the final consonant of the left side), when 하다/되다 follows a
 * noun, when a number meets its unit (10 월), or when a lone syllable / syllable+particle cannot be a word by
 * itself (개 념과, 상 태가). It only ever removes spaces, so the characters (and the error rate) are unchanged.
 *
 * PC benchmark (word-boundary F1 against the reference script; SenseVoice output, CER unchanged):
 * clean lecture 0.745 -> 0.949, classroom 0.903 -> 0.951, phone transcript 0.879 -> 0.971, held-out TTS
 * sentences 0.737 -> 0.953. Text that does not look morpheme-split (Whisper / Qwen3 output, normal writing) is
 * returned unchanged; even forced onto the reference texts the rules change nothing.
 */
object KoreanSpacing {

    fun fix(text: String): String {
        val frags = text.trim().split(WS).filter { it.isNotEmpty() }
        if (frags.size < 2 || !looksSplit(frags)) return text
        val words = ArrayList<String>(frags.size)
        words += frags[0]
        var attached = trailingParticle(frags[0])
        for (i in 1 until frags.size) {
            val frag = frags[i]
            val next = frags.getOrNull(i + 1)
            val d = decide(words.last(), words.getOrNull(words.size - 2), attached, frag, next)
            if (d == KEEP) {
                words += frag
                attached = trailingParticle(frag)
                continue
            }
            words[words.size - 1] = words.last() + frag
            attached = if (d == JOIN_BOUND) {
                val c = core(frag)
                val piece = pieces(c)?.last() ?: c
                // The copula 이 (앤 이 었 습니다) does not close the word like the subject particle does.
                if (piece == "이" && next != null && pieces(core(next))?.first() in STRONG) null else piece
            } else {
                null
            }
        }
        return words.joinToString(" ")
    }

    private const val KEEP = 0
    private const val JOIN_BOUND = 1
    private const val JOIN_OTHER = 2

    private val WS = Regex("\\s+")

    private fun set(s: String): Set<String> = s.trim().split(WS).toHashSet()

    /** Particles and endings (any sequence of them may form one fragment). */
    private val BOUND = set(
        """
        은 는 이 가 을 를 의 에 에서 에게 께 께서 한테 으로 로 로서 로써 와 과 랑 이랑 도 만 까지 부터 처럼 보다 마다 조차 마저 밖에
        나 이나 든 이든 라도 이라도 란 이란 라 이라 야 이야 요 이요 서 고 며 면 으면 으며 지 지만 는데 은데 인데 게 기 도록 려고 으려고 러 니 으니 니까
        어 아 여 어서 아서 여서 어요 아요 어야 아야 여야 죠 네요 군요 다 다고 라고 냐고 자고 는다 던 든지 거나 기에 기로 므로 으므로 음 들 님 씩 째 쯤 끼리
        었 았 였 겠 셨 습니다 니다 입니다 습니까 세요 으세요 십시오 라는 이라는 다는 는다는 이다 대로 뿐
        """,
    )

    /** Pieces that never begin a word: a right fragment is glued on only if it starts with one of these. */
    private val STRONG = set(
        """
        은 는 을 를 의 에 에서 에게 께서 께 한테 으로 로 로서 로써 와 과 처럼 까지 부터 보다 마다 조차 마저 밖에 는데 은데 지만 으며 며 면 으면
        도록 려고 으려고 려면 으려면 습니다 니다 입니다 습니까 었 았 였 겠 셨 라는 이라는 다는 라고 이라고 이며 이고 이나 이었 이다 이라 이란 이라도 이든 거나 므로 으므로
        니까 으니 다면 다가 다고 시면 시고 시는 시던 셔서 시겠 십니다 셨습니다 어서 아서 여서 어요 아요 어야 아야 여야 네요 죠 세요 으세요 게 고 서 님 씩 째 쯤 끼리
        기에 기로 기는 기도 기를
        """,
    )

    /** What may follow a glued adverbial particle (강의 에 서는, 6장 까지 이며). */
    private val STACK = set("는 은 도 만 의 서 써 요 나 이나 라도 부터 까지 에 에서 에는 에도 로 으로 이며 이고 이다 이었 입니다 이라는 이라고 이에요 라는 라고")

    /** Particles that close a noun phrase: nothing more is glued after them. */
    private val CLOSING = set("을 를 의 가 은 는 이")

    /** Particles after which another particle may follow. */
    private val NOMINAL = set("에 에서 에게 께 께서 한테 와 과 로 으로 까지 부터 처럼 보다 마다 도 만 에는 에도 에서는 로는 으로는")

    /** Derivational suffix fragments glued on although they don't start with a STRONG piece. */
    private val SUFFIX_FRAGS = set(
        """
        적으로 적인 적이고 적이며 적입니다 적이다 롭게 롭다 롭고 로운 로워 스럽게 스러운 스럽다 스럽고 스러워 답게 다운 들 들은 들이 들을 들의 들에게 들도 들과
        성이 성을 성은 성의 성과 성도 화를 화가 화는 화된 화하는 화한 화할 화합니다 화하면 화하여 화해 화해서 화하기 화되어
        """,
    )

    /** Real words that happen to split into bound pieces or a stem + ending. */
    private val EXCEPT = set(
        """
        서로 화면 도로 도시 고기 기도 들어 가지 여기 다시 나이 아니 이미 기기 지도 시기 요리 고시 게시 아이 이야기 나라 지나 다음 기로 들은 기분
        이유 이번 이상 이후 이하 이전 이내 고객 과거 과정 과연 에너지 의미 의견 의사 면적 로그 로봇 로고 도구 함께 세로 가로
        정도 온도 속도 제도 태도 의도 시도 각도 강도 빈도 용도 습도 밀도 농도 한도 고도 진도 포도 심도 감도
        """,
    )

    private val HA_STEMS = listOf("하였", "되었", "시키", "시켜", "시킨", "시킬", "시킵", "시켰", "하", "해", "합", "한", "할", "함", "했", "되", "된", "될", "됩", "됐", "돼")
    private val HA_DO_OK = set("해 돼 했 됐 하였 되었 시켜") // 해도, 돼도
    private val HA_NOT_FIRST = set("에 에서 의 이 가 와 과 로 으로 만 까지 부터 처럼 나") // 한도에, 하나 are words
    private val JI_STEMS = listOf("졌", "져", "집", "진", "질", "지")

    private val UNITS = set(
        """
        월 일 시 분 초 년 개 명 번 장 호 층 원 살 가지 퍼센트 % 점 위 차 회 주 학년 학기 시간 개월 주차 교시 배 권 쪽 페이지 대 도 세 만 천 억 조 백
        기가 메가 킬로 테라 바이트 기가바이트 메가바이트 미터 센티 킬로그램 그램 달러 번째 단계 등 차례 줄 마리 군데
        """,
    ).sortedByDescending { it.length }

    private val NUMWORD = set("한 두 세 네 다섯 여섯 일곱 여덟 아홉 열 스무 몇 여러 첫 백 천 만 수십 수백")
    private val SINO_NUM = set("일 이 삼 사 오 육 칠 팔 구 십 백 천 만 억 조 영 공")
    private val DETERMINERS = NUMWORD + set("이 그 저 새 헌 각 매 전 총 약 모든 온 본")
    private val COUNTERS = set("개 장 명 번 권 살 원 층 호 쪽 배 칸 잔 병 대 회 차 점 분 초 줄 곳 조 마리 군데")
    private val COUNTER_NEXT = (COUNTERS + set("가지 시간 사람 번째 달 주 해 학기")).toList()

    /** One-syllable words that stand on their own left of a space. */
    private val STANDALONE = set(
        """
        이 그 저 한 두 세 네 첫 새 헌 각 몇 온 전 후 매 본 총 약 제 나 너 내 누 뭐 왜 또 더 덜 잘 안 못 꼭 좀 곧 늘 다 참 막 딱 꽤 및 즉 확 쭉 푹 열
        수 것 거 건 걸 게 뭔 때 줄 데 바 등 뿐 듯 채 척 일 월 년 시 날 달 해 밤 낮 봄 빛 반 물 불 돈 말 길 집 책 힘 값 뒤 앞 옆 위 밑 속 밖 끝 중 키 팀 글
        뷰 룰 앱 웹 폰 펜 맵 셀 칩 퀵 엔 앤 엠 엘 큐 비 씨 디 티 피 알 탭 툴 핀 팁 예 응 자 아 어 오 와 음 흠
        큰 긴 쓴 센 찬 든 난 된 할 갈 볼 올 될 살 클 쓸 알 본 준 탄 뜬 둔
        곡 펫 빈 색 홈 맨 켜 꺼 써 봐 줘 놔 둬 돼 타 잠 꽉 춤 표 판 칸 방 벽 창 공 국 밥 땅 돌 산 강 꽃 별 맛 옷 컵 빵 술 쌀 몸 손 귀 입 눈 꿈 피 땀 왕 숲 풀 잎 닭 쥐 곰 뱀
        """,
    )

    /** One-syllable nouns that take particles ("키를" stays "외래 키를"). */
    private val NOUN1 = set(
        """
        이 그 저 나 너 내 네 제 누 뭐 수 것 때 줄 데 바 등 일 말 물 불 돈 길 집 차 책 밥 땅 몸 손 발 눈 귀 입 코 힘 값 뒤 앞 옆 위 밑 속 안 밖 끝 중 면 키 표
        문 글 답 법 꿈 꽃 산 강 별 빛 맛 틀 팀 반 과 선 점 날 달 해 주 년 월 시 분 초 번 개 명 장 권 원 층 호 쪽 곳 살 배 대 회 칸 판 편 뷰 룰 앱 웹 폰 펜 맵 셀 칩 엔 앤
        탭 툴 핀 팁 컵 볼 공 잠 피 비 씨 디 티 큐 왜 뭘 걸 건 둘 셋 넷 몫 쌀 술 옷 약 군 조 행 열 식 축 변 각 역 상 짝 예 전 후 밤 낮 봄 새 곡 펫 색 홈 방 벽 창 국 돌
        숲 풀 잎 닭 쥐 곰 뱀 춤 땀 왕
        """,
    )

    /** Verb/adjective stems: "있는", "많이" are not syllable + particle. */
    private val VERBSTEM = set("다 푸 여 있 없 않 같 많 좋 싫 높 낮 깊 넓 좁 작 받 찾 읽 먹 잡 닫 알 갈 볼 될 올 할 살 쓸 클 굳 맞 늦 빠 짧 길 크 쉽 나 가 오 보 주 두 쓰 서")
    private val E_PART = set("은 는 이 도 을 를 의 에 에서 에게 와 과 로 으로 나 이나 까지 부터 처럼")
    private val E_STACK = set("는 도 만 의")
    private val ADV_I = set("또는 또한 그는 이는 저는 같이 많이 없이 높이 깊이 굳이 길이 넓이 깨끗이 같은 많은 없는 있는 않는")

    /** Adverbs and pronouns after which 하다 / a subject particle starts afresh. */
    private val ADVERBS = set(
        """
        다시 지금 이제 먼저 같이 함께 모두 계속 아직 바로 그냥 정말 진짜 많이 너무 아주 매우 가장 제일 자주 이미 이렇게 그렇게 저렇게 어떻게 직접 서로 미리
        각각 따로 혼자 빨리 천천히 열심히 오늘 내일 어제 처음 여기 거기 저기 이것 그것 저것 다음 무엇 어디 언제 그럼 그래서 그리고 하지만 또는 일단 우선 대충 반드시 항상 자꾸 다들
        이거 그거 저거 이건 그건 아무것도 뭔가 어떤 그런 이런 저런 조금 약간 엄청 되게 잠깐 잠시 혹시 만약 마치 거의 전혀 별로 결국 드디어 특히 주로 보통 대부분 다른 이번 지난 매번
        여러분 우리 저희 왜냐하면 제가 내가 네가 누가
        """,
    )

    private const val VERBAL_LAST = "야서고게면며어아여는은을를려러니데듯히든던까네군냐세요죠다지만록"
    private const val NOUN_FINAL_OK = "가의과" // 추가 / 주의 / 통과 + 하다
    private const val SUBJ_BLOCK = "를는은을한된할될던인운있없면만본간갈볼알줄럼음"
    private const val ENDINGISH = "야서고게면며어아여는은을를의에가이다요죠와과로만록려러니데듯께히든던까네군냐세도"
    private const val PARTICLE_FINAL = "을를은는의에가이와과로도만"

    /** Initials ruled out at the start of native and Sino-Korean words (두음법칙): 저 녁에는, 성 장률. */
    private const val NO_INITIAL = "녀녁녕념뇨뉴랴려력련렬렴렵령례료룡류륙륜률륭"
    private const val AUX_JI_AFTER = "아어워와해여"
    private const val PUNCT_END = ".,?!;:…)]}\"'"

    private val BOUND_NOUN_START = listOf("수", "것", "거", "겁", "건", "걸", "게", "때", "줄", "데", "바", "뿐", "듯", "적", "만큼", "대로", "동안", "경우", "정도", "다음", "후", "뒤", "중", "리", "지")
    private val WEEK_PREV = set("다음 이번 지난 매 저번 첫째 둘째 셋째 넷째 한 두 세 네 몇 일 이 그 저 다다음 지지난")
    private val NEED_BATCHIM = set("은 이 을 과 으로 으로는 으로서 으로써 으며 으면 으니 으려고 으려면 으세요 으므로")
    private val NO_BATCHIM = set("가 를 와 로 로서 로써 로는 라는 라고 나 며 면 려고 려면")
    private val PARTICLE_TAIL = listOf("에서", "에게", "까지", "부터", "처럼", "보다", "마다", "한테", "께서")
    private val SENTENCE_END = listOf("니다", "니까", "세요", "어요", "아요", "에요", "예요", "죠", "네요", "군요")

    /** A split particle/ending right after a word: only SenseVoice-style text gets touched. */
    private val GATE = set("은 는 을 를 의 에 에서 습니다 니다 었 겠 았 였 으로 와 과 입니다 이라는 라는 에게 처럼 까지 부터")

    // ---- character helpers ----

    private fun isHangul(c: Char) = c in '가'..'힣'
    private fun finalOf(c: Char) = (c - '가') % 28
    private fun hasBatchim(c: Char) = finalOf(c) != 0
    private fun isRieul(c: Char) = finalOf(c) == 8
    private fun allHangul(s: String) = s.isNotEmpty() && s.all(::isHangul)
    private fun hangulPrefix(s: String) = s.takeWhile(::isHangul)

    /** [f] without trailing punctuation. */
    private fun core(f: String): String = f.dropLastWhile { !(isHangul(it) || it.isLetterOrDigit() || it == '%') }

    private fun isNumber(w: String) =
        w.isNotEmpty() && (w.last().isDigit() || w in NUMWORD || w.all { it.toString() in SINO_NUM })

    /** A particle written right after a non-Hangul word (pet.json을, ACID를) closes it like a glued one. */
    private fun trailingParticle(f: String): String? {
        if (f.length < 2) return null
        val last = f.last().toString()
        val before = f[f.length - 2]
        return if (last in CLOSING && !isHangul(before) && before.isLetterOrDigit()) last else null
    }

    /** Splits [s] into bound pieces (longest first at each step); null if it can't be. */
    private fun pieces(s: String): List<String>? {
        if (s.isEmpty()) return null
        val best = arrayOfNulls<List<String>>(s.length + 1)
        best[0] = emptyList()
        for (i in s.indices) {
            val head = best[i] ?: continue
            for (j in minOf(s.length, i + 6) downTo i + 1) {
                val piece = s.substring(i, j)
                if (best[j] == null && (piece in BOUND || piece in STRONG)) best[j] = head + piece
            }
        }
        return best[s.length]
    }

    /** Particle allomorphs: 은/이/을/과/으로 after a final consonant, 는/가/를/와/로 after a vowel (로 also after ㄹ). */
    private fun agrees(lc: Char, first: String): Boolean {
        if (!isHangul(lc)) return true
        if (first in NEED_BATCHIM) return hasBatchim(lc) && !(first.startsWith("으") && isRieul(lc))
        if (first in NO_BATCHIM) return !hasBatchim(lc) || (first[0] in "로며면려" && isRieul(lc))
        return true
    }

    private fun stemThenBound(r: String, stems: List<String>) =
        stems.any { r == it || (r.startsWith(it) && pieces(r.substring(it.length)) != null) }

    private fun startsUnit(r: String) = r !in EXCEPT && stemThenBound(r, UNITS)

    private fun startsCounter(n: String) = stemThenBound(hangulPrefix(n), COUNTER_NEXT)

    /** 하다/되다/시키다 forms: the stem alone or followed by endings (not by particles: 한도에, 하나). */
    private fun haForm(r: String): Boolean = HA_STEMS.any { st ->
        r == st || (r.startsWith(st) && pieces(r.substring(st.length))?.let { p ->
            p[0] !in HA_NOT_FIRST && !(p[0] == "도" && st !in HA_DO_OK)
        } == true)
    }

    private fun looksSplit(frags: List<String>): Boolean {
        for (i in 1 until frags.size) {
            val r = core(frags[i])
            val l = core(frags[i - 1])
            if (r in GATE) return true
            if (l.lastOrNull()?.isDigit() == true && startsUnit(hangulPrefix(r))) return true
        }
        return false
    }

    /** Whether [r] is a particle/ending fragment to glue onto [l]; [attached] is the bound piece last glued onto [l]. */
    private fun isBound(l: String, r: String, next: String?, attached: String?): Boolean {
        if (!allHangul(r) || r in EXCEPT) return false
        val lc = l.last()
        val n = next?.let(::core).orEmpty()
        if (attached in CLOSING) return false
        if (attached in NOMINAL || PARTICLE_TAIL.any { l.endsWith(it) }) {
            // after a particle only another particle may follow
            val p = pieces(r) ?: return false
            return p[0] in STACK && !(r == "만" && startsUnit(hangulPrefix(n)))
        }
        when (r) {
            "이" -> {
                val p = if (allHangul(n) && n !in EXCEPT) pieces(n) else null
                if (p != null && p[0] in STRONG) return true // copula: 앤 이 었 습니다
                // subject particle: 테이블 이 모두 (not the demonstrative after an adverb or a verb form)
                return (l.length >= 2 || l in NOUN1) && allHangul(l) && hasBatchim(lc) && lc !in SUBJ_BLOCK &&
                    l !in ADVERBS && n !in SINO_NUM
            }
            "어", "아", "여" -> return isHangul(lc) && !hasBatchim(lc) && l.length >= 2
            "다" -> return false
            "도", "만", "가" -> {
                if (l == "뿐" && r == "만") return true
                if (l.length < 2 || !allHangul(l) || lc in ENDINGISH || l in ADVERBS) return false
                return when (r) {
                    "만" -> !startsUnit(hangulPrefix(n))
                    "가" -> !hasBatchim(lc)
                    else -> true
                }
            }
        }
        if (r in SUFFIX_FRAGS) return true
        val p = pieces(r) ?: return false
        if (p[0] !in STRONG || !agrees(lc, p[0])) return false
        // 한 게 / 할 게 mean 것이 (a bound noun), not the ending -게
        return !(p[0] == "게" && finalOf(lc) == 4)
    }

    private fun decide(word: String, prevWord: String?, attached: String?, frag: String, next: String?): Int {
        if (word.isEmpty() || word.last() in PUNCT_END) return KEEP
        if (!(isHangul(frag[0]) || frag[0] == '%')) return KEEP
        val l = word
        val r = core(frag)
        if (r.isEmpty() || SENTENCE_END.any { l.endsWith(it) }) return KEEP
        val lc = l.last()
        if (lc.isDigit() && startsUnit(r)) return JOIN_OTHER
        if (isBound(l, r, next, attached)) return JOIN_BOUND
        if (!isHangul(lc) || !allHangul(r)) return KEEP
        val afterParticle = attached in CLOSING || attached in NOMINAL || PARTICLE_TAIL.any { l.endsWith(it) }
        val n = next?.let(::core).orEmpty()

        // noun + 하다/되다/시키다 (방지 하기, 진행 됩니다), not after an ending (해야 합니다)
        if (haForm(r) && r !in EXCEPT) {
            val ok = l.length >= 2 && allHangul(l) && !afterParticle && l !in ADVERBS &&
                (lc !in ENDINGISH || (lc in NOUN_FINAL_OK && l.length == 2)) &&
                !(r in setOf("한", "할", "된", "될") && startsCounter(n))
            return if (ok) JOIN_OTHER else KEEP
        }
        // auxiliary 지다 after -아/-어 (좋아 졌어요)
        if (lc in AUX_JI_AFTER && l.length >= 2 && stemThenBound(r, JI_STEMS) && r !in EXCEPT) return JOIN_OTHER
        if (r[0] in NO_INITIAL && !afterParticle) return JOIN_OTHER
        // a lone syllable that is not a word by itself (개 념과, 관 계형)
        if (l.length == 1 && l !in setOf("을", "를", "은", "는") && !(l in SINO_NUM && startsUnit(r))) {
            val prev = prevWord?.let(::core).orEmpty()
            val standalone = l in STANDALONE || (l == "만" && startsUnit(r)) || (l == "주" && prev in WEEK_PREV) ||
                (l in COUNTERS && isNumber(prev))
            if (!standalone && BOUND_NOUN_START.none { r.startsWith(it) }) return JOIN_OTHER
        }
        // one syllable + particle where the syllable is not a noun (상 태가, 성 능이)
        val head = r[0].toString()
        if (r.length >= 2 && !afterParticle && l !in DETERMINERS && r !in ADV_I && r !in ADVERBS && r !in EXCEPT &&
            !(l.length >= 3 && (lc in VERBAL_LAST || lc in PARTICLE_FINAL)) &&
            head !in NOUN1 && head !in VERBSTEM && head !in HA_STEMS && head !in SINO_NUM && finalOf(r[0]) != 20 // ㅆ: 났을
        ) {
            val p = pieces(r.substring(1))
            if (p != null && p.size <= 2 && p[0] in E_PART && agrees(r[0], p[0]) && (p.size == 1 || p[1] in E_STACK)) {
                return JOIN_OTHER
            }
        }
        return KEEP
    }
}
