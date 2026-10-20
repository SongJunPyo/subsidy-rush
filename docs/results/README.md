# 기록과 측정 결과

Phase별 작업 기록과 병목 측정 결과를 모아 두는 폴더입니다.

## 진행 현황

| Phase | 내용 | 기록 | 상태 | 태그 |
| --- | --- | --- | --- | --- |
| P0 | 환경 구축 | [p0-environment.md](p0-environment.md) | ✅ 완료 (2026-10-19) | |
| P1 | 데이터 생성·적재 | `p1-loading.md` | | |
| P2 | 기준선 | `p2-baseline.md` | | `baseline` |
| P3 | 병목 1: 중복 신청 | `b1-duplicate.md` | | `b1-before`, `b1-after` |
| P4 | 병목 2: 예산 초과 지급 | `b2-overspend.md` | | `b2-before`, `b2-after` |
| P5 | 병목 3: 조회 풀스캔 | `b3-fullscan.md` | | `b3-before`, `b3-after` |
| P6 | 병목 4: 핫 로우 락 경합·커넥션 풀 고갈 | `b4-hot-row.md` | | `b4-before`, `b4-after` |
| P7 | 병목 5: 외부 API 지연 | `b5-external-latency.md` | | `b5-before`, `b5-after` |
| P8 | 병목 6: 오픈 스파이크 + GCP 이전 | `b6-spike.md` | | `b6-before`, `b6-after` |
| P9 | 병목 7: 장애 중 정합성 | `b7-consistency.md` | | `b7-before`, `b7-after` |
| P10 | 최종 측정·정리 | `p10-final.md` | | |

파일명 중 `p1-loading.md`는 SPEC 9절에, `bN-<이름>.md` 형식은 SPEC 11.3절에 정해져 있습니다. 나머지 `<이름>` 부분은 임시로 붙인 것이니 해당 Phase를 시작할 때 바꿔도 됩니다.

## 파일 규칙

- **병목 Phase(P3~P9)**: `bN-<이름>.md`. SPEC 11.3절 결과 템플릿(환경 → 문제 재현 → 측정 결과 → 원인 분석 → 해결안 → 트레이드오프)을 따릅니다.
- **그 밖의 Phase(P0~P2, P10)**: `pN-<이름>.md`. 목표·완료 조건 → 환경 → 결정 사항 → 구성 결과 → 문제와 해결 → 검증 기록 → 다음 Phase로 넘기는 것. 예시는 [p0-environment.md](p0-environment.md)입니다.
- **Grafana 캡처**: `img/` 폴더에 `bN-<내용>.png` 형식으로 저장합니다.
- **수치는 실제 측정값만** 적습니다. 측정하지 않은 값은 비워 둡니다.
- **불변식(`/admin/verify`)이 하나라도 실패한 측정은 "실패"로 기록합니다.**
